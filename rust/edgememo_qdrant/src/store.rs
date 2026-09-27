use std::num::NonZeroUsize;
use std::path::Path;

use qdrant_edge::external::serde_json;
use qdrant_edge::{
    CountRequest, Distance, EdgeConfig, EdgeConfigBuilder, EdgeVectorParamsBuilder, NamedQuery,
    PointId, PointInsertOperations, PointOperations, PointStruct, QueryEnum, QueryRequest,
    ScoredPoint, ScoringQuery, UpdateOperation, VectorInternal, Vectors, WalOptions,
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