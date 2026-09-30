use std::num::NonZeroUsize;
use std::path::Path;

use qdrant_edge::external::serde_json;
use qdrant_edge::{
    CountRequest, CountRequestBuilder, CreateIndex, Distance, EdgeConfig, EdgeConfigBuilder,
    EdgeVectorParamsBuilder, FieldIndexOperations, Filter, JsonPath, NamedQuery,
    PayloadFieldSchema, PayloadSchemaType, PointId, PointInsertOperations, PointOperations,
    PointStruct, QueryEnum, QueryRequest, QueryRequestBuilder, Record, RetrieveRequestBuilder,
    ScoredPoint, ScoringQuery, ScrollRequestBuilder, UpdateOperation, VectorInternal, Vectors,
    WalOptions, WithPayloadInterface, WithVector,
};

use crate::error::{EdgeError, Result};

pub use qdrant_edge::EdgeShard;

pub const VECTOR_NAME: &str = "semantic";

/// WAL segments of qdrant-edge default to 32MiB. Smaller capacity keeps the
/// edge footprint reasonable on mobile devices.
const WAL_SEGMENT_CAPACITY: usize = 4 * 1024 * 1024;

pub fn build_config(dimension: usize) -> EdgeConfig {
    EdgeConfigBuilder::new()
        .on_disk_payload(false)
        .vector(
            VECTOR_NAME,
            EdgeVectorParamsBuilder::new(dimension, Distance::Cosine).build(),
        )
        .wal_options(WalOptions {
            segment_capacity: WAL_SEGMENT_CAPACITY,
            segment_queue_len: 0,
            retain_closed: NonZeroUsize::new(1).expect("1 is non-zero"),
        })
        .build()
}

fn vector_dimension(shard: &EdgeShard) -> usize {
    shard
        .config()
        .vectors
        .get(VECTOR_NAME)
        .map(|params| params.size)
        .unwrap_or(0)
}

fn check_dimension(shard: &EdgeShard, vector: &[f32]) -> Result<()> {
    let expected = vector_dimension(shard);
    if expected > 0 && vector.len() != expected {
        return Err(EdgeError::DimensionMismatch {
            expected,
            got: vector.len(),
        });
    }
    Ok(())
}

/// Build the named-vector representation accepted by qdrant-edge for both
/// vectored and payload-only points. An empty named-vector map means that the
/// point has no dense vector; it is not a zero-length dense vector and must not
/// be sent through the configured dimension check.
fn vectors_for_optional(vector: Option<&[f32]>) -> Vectors {
    match vector {
        Some(values) => Vectors::new_named([(VECTOR_NAME.to_string(), values.to_vec())]),
        None => Vectors::new_named(std::iter::empty::<(String, Vec<f32>)>()),
    }
}

fn parse_point_id(id: &str) -> Result<PointId> {
    id.parse::<PointId>().map_err(|_| EdgeError::InvalidId(id.to_string()))
}

pub fn create(path: &Path, dimension: usize) -> Result<EdgeShard> {
    EdgeShard::new(path, build_config(dimension)).map_err(EdgeError::Qdrant)
}

pub fn open(path: &Path) -> Result<EdgeShard> {
    let config = match EdgeConfig::load(path) {
        Some(Ok(config)) => Some(config),
        Some(Err(err)) => return Err(EdgeError::Qdrant(err)),
        None => None,
    };
    EdgeShard::load(path, config).map_err(EdgeError::Qdrant)
}

pub fn upsert(shard: &EdgeShard, id: &str, vector: &[f32]) -> Result<()> {
    check_dimension(shard, vector)?;
    let point_id = parse_point_id(id)?;
    let vectors = Vectors::new_named([(VECTOR_NAME.to_string(), vector.to_vec())]);
    let point = PointStruct::new(point_id, vectors, serde_json::json!({}));
    let operation = UpdateOperation::PointOperation(PointOperations::UpsertPoints(
        PointInsertOperations::PointsList(vec![point.into()]),
    ));
    shard.update(operation).map_err(EdgeError::Qdrant)
}

pub fn delete(shard: &EdgeShard, id: &str) -> Result<()> {
    let point_id = parse_point_id(id)?;
    let operation = UpdateOperation::PointOperation(PointOperations::DeletePoints {
        ids: vec![point_id],
    });
    shard.update(operation).map_err(EdgeError::Qdrant)
}

pub fn search(shard: &EdgeShard, query: &[f32], limit: usize) -> Result<Vec<ScoredPoint>> {
    check_dimension(shard, query)?;
    let mut request = QueryRequest::new(limit.max(1));
    request.query = Some(ScoringQuery::Vector(QueryEnum::Nearest(NamedQuery::new(
        VectorInternal::Dense(query.to_vec()),
        VECTOR_NAME,
    ))));
    shard.query(request).map_err(EdgeError::Qdrant)
}

pub fn count(shard: &EdgeShard) -> Result<usize> {
    shard.count(CountRequest::default()).map_err(EdgeError::Qdrant)
}

pub fn optimize(shard: &EdgeShard) -> Result<()> {
    shard.optimize().map_err(EdgeError::Qdrant)?;
    Ok(())
}

/// Force a synchronous flush of WAL + segments. qdrant-edge only persists on
/// graceful Drop or explicit flush, and Android process deaths run neither;
/// the Android store flushes after every write batch to keep vectors
/// retrievable after restarts.
pub fn flush(shard: &EdgeShard) -> Result<()> {
    shard.flush().map_err(EdgeError::Qdrant)
}

/// Upsert a point carrying both a dense vector and a JSON-object payload.
/// This is the Phase-10 spike primitive: one record = one point, and the
/// payload is what must survive an Android process restart.
pub fn upsert_with_payload(
    shard: &EdgeShard,
    id: &str,
    vector: &[f32],
    payload: serde_json::Value,
) -> Result<()> {
    check_dimension(shard, vector)?;
    upsert_with_optional_payload_vector(shard, id, Some(vector), payload)
}

/// Upsert a payload-only point. qdrant-edge represents an absent vector as an
/// empty named-vector map. This deliberately does not call `check_dimension`:
/// an absent vector is different from a malformed dense vector of dimension 0.
pub fn upsert_payload_only(
    shard: &EdgeShard,
    id: &str,
    payload: serde_json::Value,
) -> Result<()> {
    upsert_with_optional_payload_vector(shard, id, None, payload)
}

fn upsert_with_optional_payload_vector(
    shard: &EdgeShard,
    id: &str,
    vector: Option<&[f32]>,
    payload: serde_json::Value,
) -> Result<()> {
    if !payload.is_object() {
        return Err(EdgeError::InvalidPayload(
            "payload must be a JSON object".to_string(),
        ));
    }
    let point_id = parse_point_id(id)?;
    let point = PointStruct::new(point_id, vectors_for_optional(vector), payload);
    let operation = UpdateOperation::PointOperation(PointOperations::UpsertPoints(
        PointInsertOperations::PointsList(vec![point.into()]),
    ));
    shard.update(operation).map_err(EdgeError::Qdrant)
}

/// Batch upsert multiple records with payloads.
/// Input is a JSON array of objects with fields: id, vector, payload.
/// Each payload must be a JSON object. Vectors must match shard dimension.
/// This is NOT a transaction - partial failures may leave some records persisted.
pub fn upsert_batch_with_payload(
    shard: &EdgeShard,
    records_json: &str,
) -> Result<()> {
    let records: Vec<serde_json::Value> = serde_json::from_str(records_json)
        .map_err(|err| EdgeError::InvalidPayload(format!("invalid batch JSON: {err}")))?;
    
    let mut points = Vec::with_capacity(records.len());
    for record in records {
        let id = record.get("id").and_then(|v| v.as_str())
            .ok_or_else(|| EdgeError::InvalidPayload("missing id".to_string()))?;
        let vector_value = record.get("vector")
            .ok_or_else(|| EdgeError::InvalidPayload("missing vector".to_string()))?;
        let vector = match vector_value {
            serde_json::Value::Null => None,
            serde_json::Value::Array(values) => Some(values.iter()
                .map(|v| v.as_f64().unwrap_or(0.0) as f32)
                .collect::<Vec<_>>()),
            _ => return Err(EdgeError::InvalidPayload(
                "vector must be an array or null".to_string(),
            )),
        };
        let payload = record.get("payload")
            .ok_or_else(|| EdgeError::InvalidPayload("missing payload".to_string()))?;

        if !payload.is_object() {
            return Err(EdgeError::InvalidPayload("payload must be a JSON object".to_string()));
        }
        if let Some(values) = vector.as_deref() {
            check_dimension(shard, values)?;
        }
        let point_id = parse_point_id(id)?;
        let point = PointStruct::new(point_id, vectors_for_optional(vector.as_deref()), payload.clone());
        points.push(point.into());
    }

    let operation = UpdateOperation::PointOperation(PointOperations::UpsertPoints(
        PointInsertOperations::PointsList(points),
    ));
    shard.update(operation).map_err(EdgeError::Qdrant)
}

/// Retrieve points by id with their payloads and vectors for the persistence
/// spike. Missing ids are simply absent (qdrant-edge semantics).
pub fn retrieve(shard: &EdgeShard, ids: &[String]) -> Result<Vec<Record>> {
    let point_ids = ids
        .iter()
        .map(|id| parse_point_id(id))
        .collect::<Result<Vec<_>>>()?;
    shard
        .retrieve(
            RetrieveRequestBuilder::new(point_ids)
                .with_payload(WithPayloadInterface::Bool(true))
                .with_vector(WithVector::Bool(true))
                .build(),
        )
        .map_err(EdgeError::Qdrant)
}

/// Scroll points matching an optional payload filter (canonical Qdrant filter
/// JSON), returning records with payloads. `offset` resumes pagination from a
/// point id; empty string means "from the beginning".
pub fn scroll(
    shard: &EdgeShard,
    filter_json: Option<&str>,
    limit: usize,
    offset: Option<&str>,
) -> Result<Vec<Record>> {
    let mut builder = ScrollRequestBuilder::new()
        .limit(limit.max(1))
        .with_payload(WithPayloadInterface::Bool(true));
    if let Some(filter) = parse_filter(filter_json)? {
        builder = builder.filter(filter);
    }
    let mut skip_id: Option<PointId> = None;
    let mut fetch_limit = limit.max(1);
    if let Some(offset_id) = offset {
        if !offset_id.is_empty() {
            let point_id = parse_point_id(offset_id)?;
            // VERIFIED (qdrant-edge 0.8.0): `ScrollRequest.offset` is
            // INCLUSIVE — the offset point itself is returned as the first
            // record of the next page. Fetch one extra and drop the offset
            // point to provide true exclusive pagination to Kotlin.
            skip_id = Some(point_id);
            builder = builder.offset(point_id);
            fetch_limit += 1;
        }
    }
    builder = builder.limit(fetch_limit);
    let (records, _next_offset) = shard.scroll(builder.build()).map_err(EdgeError::Qdrant)?;
    let mut records = match skip_id {
        Some(id) => records.into_iter().filter(|record| record.id != id).collect(),
        None => records,
    };
    records.truncate(limit.max(1));
    Ok(records)
}

/// Count points matching an optional payload filter.
pub fn count_filtered(shard: &EdgeShard, filter_json: Option<&str>, exact: bool) -> Result<usize> {
    let mut builder = CountRequestBuilder::new().exact(exact);
    if let Some(filter) = parse_filter(filter_json)? {
        builder = builder.filter(filter);
    }
    shard.count(builder.build()).map_err(EdgeError::Qdrant)
}

/// Create a payload index for a field. `schema` is one of the qdrant payload
/// schema type names: keyword, integer, float, bool, datetime, text, uuid,
/// geo. The index is created over existing points and persisted with the
/// shard so it survives restarts (verified by the Phase-10 reopen tests).
pub fn create_payload_index(shard: &EdgeShard, field: &str, schema: &str) -> Result<()> {
    let field_name: JsonPath = field
        .parse()
        .map_err(|_| EdgeError::InvalidIndex(format!("invalid payload field '{field}'")))?;
    let field_schema = parse_schema(schema)?;
    let operation =
        UpdateOperation::FieldIndexOperation(FieldIndexOperations::CreateIndex(CreateIndex {
            field_name,
            field_schema: Some(PayloadFieldSchema::FieldType(field_schema)),
        }));
    shard.update(operation).map_err(EdgeError::Qdrant)
}

/// Dense search with an optional payload filter, returning scored points that
/// include their payloads.
pub fn search_with_filter(
    shard: &EdgeShard,
    query: &[f32],
    limit: usize,
    filter_json: Option<&str>,
) -> Result<Vec<ScoredPoint>> {
    check_dimension(shard, query)?;
    let mut builder = QueryRequestBuilder::new(limit.max(1))
        .query(ScoringQuery::Vector(QueryEnum::Nearest(NamedQuery::new(
            VectorInternal::Dense(query.to_vec()),
            VECTOR_NAME,
        ))))
        .with_payload(WithPayloadInterface::Bool(true));
    if let Some(filter) = parse_filter(filter_json)? {
        builder = builder.filter(filter);
    }
    shard.query(builder.build()).map_err(EdgeError::Qdrant)
}

/// Parse a canonical Qdrant filter JSON document (e.g.
/// `{"must":[{"key":"severity","match":{"value":"high"}}]}`) into the crate's
/// `Filter` type. `None`, empty strings and `{}` mean "no filter".
fn parse_filter(filter_json: Option<&str>) -> Result<Option<Filter>> {
    match filter_json {
        None => Ok(None),
        Some(json) => {
            let trimmed = json.trim();
            if trimmed.is_empty() || trimmed == "{}" || trimmed == "null" {
                Ok(None)
            } else {
                serde_json::from_str::<Filter>(trimmed)
                    .map(Some)
                    .map_err(|err| {
                        EdgeError::InvalidFilter(format!("invalid filter JSON: {err}"))
                    })
            }
        }
    }
}

fn parse_schema(schema: &str) -> Result<PayloadSchemaType> {
    match schema {
        "keyword" => Ok(PayloadSchemaType::Keyword),
        "integer" => Ok(PayloadSchemaType::Integer),
        "float" => Ok(PayloadSchemaType::Float),
        "bool" => Ok(PayloadSchemaType::Bool),
        "datetime" => Ok(PayloadSchemaType::Datetime),
        "text" => Ok(PayloadSchemaType::Text),
        "uuid" => Ok(PayloadSchemaType::Uuid),
        "geo" => Ok(PayloadSchemaType::Geo),
        other => Err(EdgeError::InvalidIndex(format!(
            "unknown payload schema type '{other}'"
        ))),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;
    use std::sync::atomic::{AtomicU64, Ordering};

    const TEST_DIM: usize = 4;

    const ID_1: &str = "00000000-0000-0000-0000-000000000001";
    const ID_2: &str = "00000000-0000-0000-0000-000000000002";
    const ID_3: &str = "00000000-0000-0000-0000-000000000003";
    const ID_4: &str = "00000000-0000-0000-0000-000000000004";
    const ID_5: &str = "00000000-0000-0000-0000-000000000005";

    static COUNTER: AtomicU64 = AtomicU64::new(0);

    fn temp_dir() -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "edgememo-spike-{}-{}",
            std::process::id(),
            COUNTER.fetch_add(1, Ordering::SeqCst)
        ));
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn cleanup(dir: &PathBuf) {
        let _ = std::fs::remove_dir_all(dir);
    }

    fn record_payload(
        id: &str,
        machine_id: &str,
        severity: &str,
        status: &str,
        line: &str,
        version: i64,
    ) -> serde_json::Value {
        serde_json::json!({
            "record_type": "maintenance_record",
            "id": id,
            "version": version,
            "created_at": 1_700_000_000_000_i64,
            "updated_at": 1_700_000_000_000_i64,
            "content": "Hydraulic pump overheating on line",
            "machine_id": machine_id,
            "severity": severity,
            "status": status,
            "plant": "Jamshedpur-02",
            "line": line,
            "zone": "PRESS-04",
        })
    }

    fn payload_json(shard: &EdgeShard, id: &str) -> serde_json::Value {
        let records = retrieve(shard, &[id.to_string()]).unwrap();
        assert_eq!(records.len(), 1, "record {id} must exist");
        serde_json::to_value(records[0].payload.as_ref().unwrap()).unwrap()
    }

    fn semantic_vector(shard: &EdgeShard, id: &str) -> Vec<f32> {
        let records = retrieve(shard, &[id.to_string()]).unwrap();
        let vector = records[0].vector.as_ref().expect("retrieved vector");
        match vector {
            qdrant_edge::VectorStructInternal::Named(vectors) => match vectors.get(VECTOR_NAME) {
                Some(VectorInternal::Dense(values)) => values.clone(),
                _ => panic!("missing semantic dense vector"),
            },
            _ => panic!("expected named semantic vector"),
        }
    }

    #[test]
    fn payload_roundtrip_through_retrieve() {
        let dir = temp_dir();
        let shard = create(dir.as_path(), TEST_DIM).unwrap();
        upsert_with_payload(
            &shard,
            ID_1,
            &[1.0, 0.0, 0.0, 0.0],
            record_payload("MR-001", "M-042", "high", "open", "LINE-A", 1),
        )
        .unwrap();

        let json = payload_json(&shard, ID_1);
        assert_eq!(json["id"], "MR-001");
        assert_eq!(json["record_type"], "maintenance_record");
        assert_eq!(json["machine_id"], "M-042");
        assert_eq!(json["severity"], "high");
        assert_eq!(json["status"], "open");
        assert_eq!(json["plant"], "Jamshedpur-02");
        assert_eq!(json["line"], "LINE-A");
        assert_eq!(json["zone"], "PRESS-04");
        assert_eq!(json["version"], 1);
        drop(shard);
        cleanup(&dir);
    }

    #[test]
    fn payload_and_vector_survive_reopen() {
        let dir = temp_dir();
        {
            let shard = create(dir.as_path(), TEST_DIM).unwrap();
            upsert_with_payload(
                &shard,
                ID_1,
                &[1.0, 0.0, 0.0, 0.0],
                record_payload("MR-001", "M-042", "high", "open", "LINE-A", 1),
            )
            .unwrap();
            flush(&shard).unwrap();
        }
        {
            let shard = open(dir.as_path()).unwrap();
            let json = payload_json(&shard, ID_1);
            assert_eq!(json["id"], "MR-001");
            assert_eq!(json["severity"], "high");
            assert_eq!(semantic_vector(&shard, ID_1), vec![1.0, 0.0, 0.0, 0.0]);

            let results = search_with_filter(&shard, &[0.9, 0.1, 0.1, 0.0], 4, None).unwrap();
            assert_eq!(results.len(), 1);
            assert_eq!(results[0].id.to_string(), ID_1);
            assert!(results[0].score > 0.9);
            let scored_payload =
                serde_json::to_value(results[0].payload.as_ref().unwrap()).unwrap();
            assert_eq!(scored_payload["machine_id"], "M-042");
        }
        cleanup(&dir);
    }

    #[test]
    fn payload_only_point_persists_without_zero_vector() {
        let dir = temp_dir();
        {
            let shard = create(dir.as_path(), TEST_DIM).unwrap();
            create_payload_index(&shard, "_record_type", "keyword").unwrap();
            create_payload_index(&shard, "operation_id", "keyword").unwrap();
            create_payload_index(&shard, "_state", "keyword").unwrap();
            upsert_payload_only(
                &shard,
                ID_5,
                serde_json::json!({
                    "_record_type": "outbox_op",
                    "operation_id": "UPSERT:123e4567-e89b-12d3-a456-426614174000:3",
                    "_state": "PENDING",
                    "payload_content": "policy-approved content",
                }),
            )
            .unwrap();
            flush(&shard).unwrap();
        }
        {
            let shard = open(dir.as_path()).unwrap();
            let records = retrieve(&shard, &[ID_5.to_string()]).unwrap();
            assert_eq!(records.len(), 1);
            assert!(matches!(
                records[0].vector.as_ref(),
                Some(qdrant_edge::VectorStructInternal::Named(vectors)) if vectors.is_empty()
            ));
            let payload = payload_json(&shard, ID_5);
            assert_eq!(payload["operation_id"], "UPSERT:123e4567-e89b-12d3-a456-426614174000:3");
            assert_eq!(payload["payload_content"], "policy-approved content");

            let pending = scroll(
                &shard,
                Some(r#"{"must":[{"key":"_state","match":{"value":"PENDING"}}]}"#),
                10,
                None,
            )
            .unwrap();
            assert_eq!(pending.len(), 1);
            assert_eq!(count_filtered(&shard, None, true).unwrap(), 1);
        }
        cleanup(&dir);
    }

    /// Parses and evaluates the exact filter JSON shapes emitted by the
    /// Kotlin `FilterCompiler` against a real shard: top-level `should`
    /// (any-of), nested `should` inside `must`/`must_not`, `min_should`,
    /// `is_null`, `range`, and the empty-`should` (match-nothing) form.
    /// These are Phase 12B.5 checks: compiler output must be accepted by
    /// qdrant-edge 0.8.0 AND evaluate with correct boolean semantics.
    #[test]
    fn filter_compiler_shapes_parse_and_evaluate_on_real_shard() {
        let dir = temp_dir();
        let shard = create(dir.as_path(), TEST_DIM).unwrap();
        create_payload_index(&shard, "record_type", "keyword").unwrap();
        create_payload_index(&shard, "severity", "keyword").unwrap();
        create_payload_index(&shard, "status", "keyword").unwrap();
        create_payload_index(&shard, "line", "keyword").unwrap();
        create_payload_index(&shard, "version", "integer").unwrap();
        create_payload_index(&shard, "created_at", "integer").unwrap();
        for (id, vector, severity, status, line) in [
            (ID_1, vec![1.0, 0.0, 0.0, 0.0], "high", "open", "LINE-A"),
            (ID_2, vec![0.0, 1.0, 0.0, 0.0], "low", "resolved", "LINE-B"),
            (ID_3, vec![0.0, 0.0, 1.0, 0.0], "high", "resolved", "LINE-A"),
            (ID_4, vec![0.0, 0.0, 0.0, 1.0], "critical", "open", "LINE-A"),
        ] {
            let mut payload = record_payload("unused", "M-042", severity, status, line, 1);
            payload["id"] = serde_json::json!(match id {
                ID_1 => "MR-1",
                ID_2 => "MR-2",
                ID_3 => "MR-3",
                _ => "MR-4",
            });
            upsert_with_payload(&shard, id, &vector, payload).unwrap();
        }

        let ids = |records: Vec<Record>| -> Vec<String> {
            let mut out: Vec<String> = records
                .into_iter()
                .map(|record| {
                    serde_json::to_value(record.payload.as_ref().unwrap()).unwrap()["id"]
                        .as_str()
                        .unwrap()
                        .to_string()
                })
                .collect();
            out.sort();
            out
        };

        // Match (compiler: {"must":[cond]})
        assert_eq!(
            ids(scroll(&shard, Some(r#"{"must":[{"key":"severity","match":{"value":"high"}}]}"#), 10, None).unwrap()),
            vec!["MR-1", "MR-3"]
        );
        // In (compiler: {"should":[per-value matches]})
        assert_eq!(
            ids(scroll(&shard, Some(r#"{"should":[{"key":"severity","match":{"value":"low"}},{"key":"severity","match":{"value":"critical"}}]}"#), 10, None).unwrap()),
            vec!["MR-2", "MR-4"]
        );
        // Range (compiler: {"must":[range]})
        assert_eq!(
            ids(scroll(&shard, Some(r#"{"must":[{"key":"version","range":{"gte":1.0,"lt":2.0}}]}"#), 10, None).unwrap()),
            vec!["MR-1", "MR-2", "MR-3", "MR-4"]
        );
        // DateRange (compiler: {"must":[range with gt/lt]} over created_at millis)
        assert_eq!(
            ids(scroll(&shard, Some(r#"{"must":[{"key":"created_at","range":{"gt":1699000000000.0,"lt":1701000000000.0}}]}"#), 10, None).unwrap()),
            vec!["MR-1", "MR-2", "MR-3", "MR-4"]
        );
        // Exists (compiler: {"must_not":[{"is_empty":{"key":k}}]} — VERIFIED:
        // `is_null:false` also matches MISSING fields, so existence is the
        // negation of `is_empty`).
        assert_eq!(
            ids(scroll(&shard, Some(r#"{"must_not":[{"is_empty":{"key":"line"}}]}"#), 10, None).unwrap()),
            vec!["MR-1", "MR-2", "MR-3", "MR-4"]
        );
        assert_eq!(
            ids(scroll(&shard, Some(r#"{"must_not":[{"is_empty":{"key":"_subject_key"}}]}"#), 10, None).unwrap()),
            Vec::<String>::new()
        );
        // Not (compiler: {"must_not":[cond]})
        assert_eq!(
            ids(scroll(&shard, Some(r#"{"must_not":[{"key":"severity","match":{"value":"high"}}]}"#), 10, None).unwrap()),
            vec!["MR-2", "MR-4"]
        );
        // And (compiler: {"must":[conds]})
        assert_eq!(
            ids(scroll(&shard, Some(r#"{"must":[{"key":"severity","match":{"value":"high"}},{"key":"status","match":{"value":"resolved"}}]}"#), 10, None).unwrap()),
            vec!["MR-3"]
        );
        // Or nested inside And: (severity=low OR severity=critical) AND line=LINE-A → only MR-4
        assert_eq!(
            ids(scroll(
                &shard,
                Some(
                    r#"{"must":[
                        {"key":"line","match":{"value":"LINE-A"}},
                        {"should":[
                            {"key":"severity","match":{"value":"low"}},
                            {"key":"severity","match":{"value":"critical"}}
                        ]}
                    ]}"#
                ),
                10,
                None,
            )
            .unwrap()),
            vec!["MR-4"]
        );
        // (A OR B) AND (C OR D): (high OR critical) AND (open OR resolved) → MR-1, MR-3, MR-4
        assert_eq!(
            ids(scroll(
                &shard,
                Some(
                    r#"{"must":[
                        {"should":[
                            {"key":"severity","match":{"value":"high"}},
                            {"key":"severity","match":{"value":"critical"}}
                        ]},
                        {"should":[
                            {"key":"status","match":{"value":"open"}},
                            {"key":"status","match":{"value":"resolved"}}
                        ]}
                    ]}"#
                ),
                10,
                None,
            )
            .unwrap()),
            vec!["MR-1", "MR-3", "MR-4"]
        );
        // Native `min_should` (the crate-supported spelling of minimum_should_match)
        assert_eq!(
            ids(scroll(
                &shard,
                Some(
                    r#"{"min_should":{
                        "conditions":[
                            {"key":"severity","match":{"value":"high"}},
                            {"key":"status","match":{"value":"open"}}
                        ],
                        "min_count":2
                    }}"#
                ),
                10,
                None,
            )
            .unwrap()),
            vec!["MR-1"]
        );
        // Empty should = match-ALL. VERIFIED engine semantics:
        // OptimizedFilter::should is "at least one ... if not empty"
        // (qdrant-edge 0.8.0 optimized_filter.rs:10-11) — matching the
        // Qdrant server rule. The Kotlin compiler emits this shape for
        // empty In/Or sets; it must parse and be a no-op.
        assert_eq!(
            ids(scroll(&shard, Some(r#"{"should":[]}"#), 10, None).unwrap()),
            vec!["MR-1", "MR-2", "MR-3", "MR-4"]
        );
        assert_eq!(count_filtered(&shard, Some(r#"{"should":[]}"#), true).unwrap(), 4);
        // `minimum_should_match` must be REJECTED (unknown field): pins the 12A §20A defect.
        assert!(parse_filter(Some(
            r#"{"should":[{"key":"severity","match":{"value":"high"}}],"minimum_should_match":1}"#
        ))
        .is_err());

        // Filtered search accepts the same nested-should shape.
        let hits = search_with_filter(
            &shard,
            &[0.9, 0.1, 0.1, 0.0],
            4,
            Some(
                r#"{"must":[
                    {"should":[
                        {"key":"severity","match":{"value":"high"}},
                        {"key":"severity","match":{"value":"critical"}}
                    ]}
                ]}"#,
            ),
        )
        .unwrap();
        let mut hit_ids: Vec<String> = hits
            .iter()
            .map(|h| {
                serde_json::to_value(h.payload.as_ref().unwrap()).unwrap()["id"]
                    .as_str()
                    .unwrap()
                    .to_string()
            })
            .collect();
        hit_ids.sort();
        assert_eq!(hit_ids, vec!["MR-1", "MR-3", "MR-4"]);

        drop(shard);
        cleanup(&dir);
    }

    /// Mixed collection: vector-bearing and payload-only points in one shard.
    /// Upsert, retrieve, scroll, count, filtered search, update, delete and
    /// restart all treat both record kinds consistently (Phase 12B.4).
    #[test]
    fn mixed_vector_and_payload_only_collection_survives_restart() {
        let dir = temp_dir();
        {
            let shard = create(dir.as_path(), TEST_DIM).unwrap();
            create_payload_index(&shard, "kind", "keyword").unwrap();
            upsert_with_payload(
                &shard,
                ID_1,
                &[1.0, 0.0, 0.0, 0.0],
                serde_json::json!({"kind": "vector", "severity": "high"}),
            )
            .unwrap();
            upsert_payload_only(
                &shard,
                ID_2,
                serde_json::json!({"kind": "payload_only", "severity": "high"}),
            )
            .unwrap();
            // Batch with explicit null vector for the payload-only member.
            upsert_batch_with_payload(
                &shard,
                r#"[{"id":"00000000-0000-0000-0000-000000000003","vector":null,"payload":{"kind":"payload_only","severity":"low"}},
                    {"id":"00000000-0000-0000-0000-000000000004","vector":[0.0,0.0,0.0,1.0],"payload":{"kind":"vector","severity":"low"}}]"#,
            )
            .unwrap();
            flush(&shard).unwrap();
        }
        {
            let shard = open(dir.as_path()).unwrap();
            assert_eq!(count(&shard).unwrap(), 4);
            assert_eq!(
                count_filtered(&shard, Some(r#"{"must":[{"key":"kind","match":{"value":"payload_only"}}]}"#), true)
                    .unwrap(),
                2
            );

            let records = retrieve(&shard, &[ID_1.to_string(), ID_2.to_string()]).unwrap();
            let by_id: std::collections::HashMap<String, _> = records
                .iter()
                .map(|r| (r.id.to_string(), r))
                .collect();
            assert!(matches!(
                by_id[ID_1].vector.as_ref(),
                Some(qdrant_edge::VectorStructInternal::Named(v)) if v.contains_key(VECTOR_NAME)
            ));
            assert!(matches!(
                by_id[ID_2].vector.as_ref(),
                Some(qdrant_edge::VectorStructInternal::Named(v)) if v.is_empty()
            ));

            // Search returns ONLY vector-bearing points for the semantic plane.
            let hits = search_with_filter(&shard, &[1.0, 0.0, 0.0, 0.0], 4, None).unwrap();
            let hit_ids: Vec<String> = hits.iter().map(|h| h.id.to_string()).collect();
            assert!(hit_ids.contains(&ID_1.to_string()));
            assert!(hit_ids.contains(&ID_4.to_string()));
            assert!(!hit_ids.contains(&ID_2.to_string()));
            assert!(!hit_ids.contains(&ID_3.to_string()));

            // Update a payload-only record in place; the stale value disappears.
            upsert_payload_only(
                &shard,
                ID_2,
                serde_json::json!({"kind": "payload_only", "severity": "critical"}),
            )
            .unwrap();
            flush(&shard).unwrap();
        }
        {
            let shard = open(dir.as_path()).unwrap();
            let json = payload_json(&shard, ID_2);
            assert_eq!(json["severity"], "critical");
            let high = scroll(&shard, Some(r#"{"must":[{"key":"severity","match":{"value":"high"}}]}"#), 10, None).unwrap();
            assert_eq!(high.len(), 1);
            delete(&shard, ID_2).unwrap();
            flush(&shard).unwrap();
        }
        {
            let shard = open(dir.as_path()).unwrap();
            assert_eq!(retrieve(&shard, &[ID_2.to_string()]).unwrap().len(), 0);
            assert_eq!(count(&shard).unwrap(), 3);
        }
        cleanup(&dir);
    }

    #[test]
    fn payload_filters_return_exact_sets() {
        let dir = temp_dir();
        let shard = create(dir.as_path(), TEST_DIM).unwrap();
        upsert_with_payload(
            &shard,
            ID_1,
            &[1.0, 0.0, 0.0, 0.0],
            record_payload("MR-001", "M-042", "high", "open", "LINE-A", 1),
        )
        .unwrap();
        upsert_with_payload(
            &shard,
            ID_2,
            &[0.0, 1.0, 0.0, 0.0],
            record_payload("MR-002", "M-042", "low", "resolved", "LINE-B", 1),
        )
        .unwrap();
        upsert_with_payload(
            &shard,
            ID_3,
            &[0.0, 0.0, 1.0, 0.0],
            record_payload("MR-003", "M-100", "high", "resolved", "LINE-A", 1),
        )
        .unwrap();
        upsert_with_payload(
            &shard,
            ID_4,
            &[0.0, 0.0, 0.0, 1.0],
            record_payload("MR-004", "M-200", "critical", "open", "LINE-A", 1),
        )
        .unwrap();

        let ids = |records: Vec<Record>| -> Vec<String> {
            records
                .into_iter()
                .map(|record| {
                    serde_json::to_value(record.payload.as_ref().unwrap()).unwrap()["id"]
                        .as_str()
                        .unwrap()
                        .to_string()
                })
                .collect()
        };

        let severity_high =
            scroll(&shard, Some(r#"{"must":[{"key":"severity","match":{"value":"high"}}]}"#), 10, None)
                .unwrap();
        assert_eq!(ids(severity_high), vec!["MR-001", "MR-003"]);

        let status_resolved =
            scroll(&shard, Some(r#"{"must":[{"key":"status","match":{"value":"resolved"}}]}"#), 10, None)
                .unwrap();
        assert_eq!(ids(status_resolved), vec!["MR-002", "MR-003"]);

        let machine_042 =
            scroll(&shard, Some(r#"{"must":[{"key":"machine_id","match":{"value":"M-042"}}]}"#), 10, None)
                .unwrap();
        assert_eq!(ids(machine_042), vec!["MR-001", "MR-002"]);

        let combined = scroll(
            &shard,
            Some(
                r#"{"must":[
                    {"key":"record_type","match":{"value":"maintenance_record"}},
                    {"key":"severity","match":{"value":"high"}},
                    {"key":"line","match":{"value":"LINE-A"}}
                ]}"#,
            ),
            10,
            None,
        )
        .unwrap();
        // MR-001 (high, LINE-A) AND MR-003 (high, LINE-A) both satisfy the
        // three-condition filter from the Phase-10 task definition.
        assert_eq!(ids(combined), vec!["MR-001", "MR-003"]);

        let combined_unique = scroll(
            &shard,
            Some(
                r#"{"must":[
                    {"key":"record_type","match":{"value":"maintenance_record"}},
                    {"key":"severity","match":{"value":"high"}},
                    {"key":"line","match":{"value":"LINE-A"}},
                    {"key":"machine_id","match":{"value":"M-042"}}
                ]}"#,
            ),
            10,
            None,
        )
        .unwrap();
        assert_eq!(ids(combined_unique), vec!["MR-001"]);

        assert_eq!(count_filtered(&shard, Some(r#"{"must":[{"key":"severity","match":{"value":"high"}}]}"#), true).unwrap(), 2);
        assert_eq!(count_filtered(&shard, None, true).unwrap(), 4);
        drop(shard);
        cleanup(&dir);
    }

    #[test]
    fn payload_index_survives_reopen_and_filter_still_works() {
        let dir = temp_dir();
        {
            let shard = create(dir.as_path(), TEST_DIM).unwrap();
            create_payload_index(&shard, "record_type", "keyword").unwrap();
            create_payload_index(&shard, "machine_id", "keyword").unwrap();
            create_payload_index(&shard, "severity", "keyword").unwrap();
            create_payload_index(&shard, "status", "keyword").unwrap();
            create_payload_index(&shard, "plant", "keyword").unwrap();
            create_payload_index(&shard, "line", "keyword").unwrap();
            upsert_with_payload(
                &shard,
                ID_1,
                &[1.0, 0.0, 0.0, 0.0],
                record_payload("MR-001", "M-042", "high", "open", "LINE-A", 1),
            )
            .unwrap();
            upsert_with_payload(
                &shard,
                ID_2,
                &[0.0, 1.0, 0.0, 0.0],
                record_payload("MR-002", "M-042", "low", "resolved", "LINE-B", 1),
            )
            .unwrap();
            flush(&shard).unwrap();
        }
        {
            // Reopen WITHOUT recreating any index, then filter.
            let shard = open(dir.as_path()).unwrap();
            let info = shard.info().unwrap();
            for field in ["record_type", "machine_id", "severity", "status", "plant", "line"] {
                let path: JsonPath = field.parse().unwrap();
                assert_eq!(info.payload_schema.get(&path).unwrap().data_type, PayloadSchemaType::Keyword);
            }
            let severity_high =
                scroll(&shard, Some(r#"{"must":[{"key":"severity","match":{"value":"high"}}]}"#), 10, None)
                    .unwrap();
            assert_eq!(severity_high.len(), 1);
            let json = serde_json::to_value(severity_high[0].payload.as_ref().unwrap()).unwrap();
            assert_eq!(json["id"], "MR-001");

            let combined = scroll(
                &shard,
                Some(
                    r#"{"must":[
                        {"key":"record_type","match":{"value":"maintenance_record"}},
                        {"key":"machine_id","match":{"value":"M-042"}}
                    ]}"#,
                ),
                10,
                None,
            )
            .unwrap();
            assert_eq!(combined.len(), 2);
        }
        cleanup(&dir);
    }

    #[test]
    fn update_overwrites_payload_across_reopen() {
        let dir = temp_dir();
        {
            let shard = create(dir.as_path(), TEST_DIM).unwrap();
            upsert_with_payload(
                &shard,
                ID_1,
                &[1.0, 0.0, 0.0, 0.0],
                record_payload("MR-001", "M-042", "high", "open", "LINE-A", 1),
            )
            .unwrap();
            flush(&shard).unwrap();
        }
        {
            let shard = open(dir.as_path()).unwrap();
            let mut updated =
                record_payload("MR-001", "M-042", "critical", "open", "LINE-A", 2);
            updated["updated_at"] = serde_json::json!(1_700_000_600_000_i64);
            upsert_with_payload(&shard, ID_1, &[1.0, 0.0, 0.0, 0.0], updated).unwrap();
            flush(&shard).unwrap();
        }
        {
            let shard = open(dir.as_path()).unwrap();
            let json = payload_json(&shard, ID_1);
            assert_eq!(json["version"], 2);
            assert_eq!(json["severity"], "critical");
            assert_eq!(json["updated_at"], 1_700_000_600_000_i64);
            // The stale version-1 state must no longer be visible anywhere.
            let still_high =
                scroll(&shard, Some(r#"{"must":[{"key":"severity","match":{"value":"high"}}]}"#), 10, None)
                    .unwrap();
            assert!(still_high.is_empty(), "stale severity=high state must be gone");
            let now_critical =
                scroll(&shard, Some(r#"{"must":[{"key":"severity","match":{"value":"critical"}}]}"#), 10, None)
                    .unwrap();
            assert_eq!(now_critical.len(), 1);
        }
        cleanup(&dir);
    }

    #[test]
    fn delete_persists_across_reopen() {
        let dir = temp_dir();
        {
            let shard = create(dir.as_path(), TEST_DIM).unwrap();
            upsert_with_payload(
                &shard,
                ID_1,
                &[1.0, 0.0, 0.0, 0.0],
                record_payload("MR-001", "M-042", "high", "open", "LINE-A", 1),
            )
            .unwrap();
            flush(&shard).unwrap();
        }
        {
            let shard = open(dir.as_path()).unwrap();
            delete(&shard, ID_1).unwrap();
            flush(&shard).unwrap();
        }
        {
            let shard = open(dir.as_path()).unwrap();
            assert_eq!(retrieve(&shard, &[ID_1.to_string()]).unwrap().len(), 0);
            assert_eq!(count_filtered(&shard, None, true).unwrap(), 0);
        }
        cleanup(&dir);
    }

    /// VERIFIED (qdrant-edge 0.8.0): raw `ScrollRequest.offset` is INCLUSIVE —
    /// the offset point itself comes back as the first record of the next
    /// page. `store::scroll` compensates so Kotlin pages never overlap.
    #[test]
    fn scroll_offset_pagination_is_exclusive() {
        let dir = temp_dir();
        let shard = create(dir.as_path(), TEST_DIM).unwrap();
        let ids = [
            "11111111-1111-1111-1111-111111111111",
            "22222222-2222-2222-2222-222222222222",
            "33333333-3333-3333-3333-333333333333",
            "44444444-4444-4444-4444-444444444444",
            "55555555-5555-5555-5555-555555555555",
        ];
        for (i, id) in ids.iter().enumerate() {
            upsert_payload_only(&shard, id, serde_json::json!({"seq": i})).unwrap();
        }
        flush(&shard).unwrap();

        let filter = r#"{"must":[{"key":"seq","range":{"lt":100.0}}]}"#;
        let mut seen: Vec<String> = Vec::new();
        let mut offset: Option<String> = None;
        loop {
            let page = scroll(&shard, Some(filter), 2, offset.as_deref()).unwrap();
            for record in &page {
                seen.push(record.id.to_string());
            }
            if page.len() < 2 {
                break;
            }
            offset = Some(page.last().unwrap().id.to_string());
        }
        seen.sort();
        let mut expected: Vec<String> = ids.iter().map(|i| i.to_string()).collect();
        expected.sort();
        assert_eq!(seen, expected, "pages must partition the collection with no repeats");
        drop(shard);
        cleanup(&dir);
    }
}