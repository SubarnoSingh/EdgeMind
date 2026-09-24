use std::fmt;

use qdrant_edge::OperationError;

#[derive(Debug)]
pub enum EdgeError {
    InvalidHandle,
    InvalidId(String),
    DimensionMismatch { expected: usize, got: usize },
    Jni(String),
    Qdrant(OperationError),
    Panic(String),
}

impl fmt::Display for EdgeError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            EdgeError::InvalidHandle => write!(f, "invalid shard handle"),
            EdgeError::InvalidId(id) => write!(
                f,
                "invalid point id '{id}': must be a u64 number or a UUID string"
            ),
            EdgeError::DimensionMismatch { expected, got } => {
                write!(f, "vector dimension mismatch: expected {expected}, got {got}")
            }
            EdgeError::Jni(msg) => write!(f, "JNI error: {msg}"),
            EdgeError::Qdrant(err) => write!(f, "qdrant-edge error: {err}"),
            EdgeError::Panic(msg) => write!(f, "internal panic: {msg}"),
        }
    }
}

pub type Result<T> = std::result::Result<T, EdgeError>;

/// Run a closure catching panics, mapping them to [`EdgeError::Panic`].
pub fn guard<T>(f: impl FnOnce() -> Result<T>) -> Result<T> {
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)) {
        Ok(result) => result,
        Err(payload) => {
            let msg = payload
                .downcast_ref::<&str>()
                .map(|s| s.to_string())
                .or_else(|| payload.downcast_ref::<String>().cloned())
                .unwrap_or_else(|| "unknown panic payload".to_string());
            Err(EdgeError::Panic(msg))
        }
    }
}