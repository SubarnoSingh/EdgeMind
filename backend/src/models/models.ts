/**
 * Cloud-side knowledge model — mirrors the Android
 * `domain.cloud.CloudKnowledgeItem` 1:1 so the pull endpoint can serialize
 * directly into `CloudKnowledgeBatch`.
 */
export interface CloudKnowledgeItem {
  memoryId: string;
  subjectKey: string | null;
  title: string;
  content: string;
  contentHash: string;
  version: number;
  updatedAt: number;
  origin: string;
  authority: string | null;
  supersedes: string | null;
  tombstone: boolean;
  metadata: Record<string, string>;
}

/**
 * A device push (Android → backend → Qdrant Cloud). `title`/`content` are the
 * policy-sanctioned representation (SYNC original or SYNC_REDACTED redaction)
 * produced by the Android SyncPayloadFactory. LOCAL_ONLY rows can never reach
 * this endpoint; the backend rejects them anyway as defense in depth.
 */
export interface SyncPushRequest {
  operationId: string;
  memoryId: string;
  operationType: "UPSERT";
  title: string;
  content: string;
  memory: {
    syncDecision: "SYNC" | "SYNC_REDACTED";
    origin: "LOCAL" | "SYNCED";
    redacted: boolean;
    version: number;
    contentHash: string;
    subjectKey: string | null;
    type: string;
    tags: string[];
    chunkId: string | null;
    source: string;
    supersedes: string | null;
    tombstone: boolean;
    updatedAt: number;
    metadata: Record<string, string>;
  };
}

export interface CloudKnowledgeBatchResponse {
  items: CloudKnowledgeItem[];
  nextCursor: string | null;
}
