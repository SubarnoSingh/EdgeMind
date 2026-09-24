use std::ffi::c_void;

use jni::objects::{JClass, JFloatArray, JObject, JObjectArray, JString, JValue};
use jni::sys::{jint, jlong, JNI_VERSION_1_6};
use jni::{JNIEnv, JavaVM};

use crate::error::{guard, EdgeError, Result};
use crate::store;

const CLASS_NATIVE_BRIDGE: &str = "com/example/EdgeMemo/native/qdrant/NativeBridge";
const CLASS_SEARCH_RESULT: &str = "com/example/EdgeMemo/native/qdrant/SearchResult";
const CLASS_EXCEPTION: &str = "com/example/EdgeMemo/native/qdrant/QdrantNativeException";
const SEARCH_RESULT_CTOR: &str = "(Ljava/lang/String;D)V";

fn map_jni(err: jni::errors::Error) -> EdgeError {
    EdgeError::Jni(err.to_string())
}

fn get_string(env: &mut JNIEnv, value: &JString) -> Result<String> {
    env.get_string(value).map(|s| s.into()).map_err(map_jni)
}

fn get_f32_vector(env: &mut JNIEnv, array: &JFloatArray) -> Result<Vec<f32>> {
    let length = env.get_array_length(array).map_err(map_jni)?;
    let mut buffer = vec![0.0f32; length as usize];
    env.get_float_array_region(array, 0, &mut buffer)
        .map_err(map_jni)?;
    Ok(buffer)
}

fn shard_ptr(handle: jlong) -> Result<*mut store::EdgeShard> {
    if handle == 0 {
        Err(EdgeError::InvalidHandle)
    } else {
        Ok(handle as *mut store::EdgeShard)
    }
}

fn throw_err(env: &mut JNIEnv, err: EdgeError) {
    let _ = env.throw_new(CLASS_EXCEPTION, err.to_string());
}

fn build_search_result<'local>(
    env: &mut JNIEnv<'local>,
    id: &str,
    score: f64,
) -> Result<JObject<'local>> {
    let class = env.find_class(CLASS_SEARCH_RESULT).map_err(map_jni)?;
    let id_string = env.new_string(id).map_err(map_jni)?;
    let id_object = JObject::from(id_string);
    let args = [JValue::Object(&id_object), JValue::Double(score)];
    env.new_object(class, SEARCH_RESULT_CTOR, &args)
        .map_err(map_jni)
}

unsafe extern "system" fn native_create(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
    dimension: jint,
) -> jlong {
    let result = guard(|| {
        let path = get_string(&mut env, &path)?;
        let shard = store::create(std::path::Path::new(&path), dimension.max(1) as usize)?;
        Ok(Box::into_raw(Box::new(shard)) as jlong)
    });
    match result {
        Ok(handle) => handle,
        Err(err) => {
            throw_err(&mut env, err);
            0
        }
    }
}

unsafe extern "system" fn native_open(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jlong {
    let result = guard(|| {
        let path = get_string(&mut env, &path)?;
        let shard = store::open(std::path::Path::new(&path))?;
        Ok(Box::into_raw(Box::new(shard)) as jlong)
    });
    match result {
        Ok(handle) => handle,
        Err(err) => {
            throw_err(&mut env, err);
            0
        }
    }
}

unsafe extern "system" fn native_upsert(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    id: JString,
    vector: JFloatArray,
) {
    let result = guard(|| {
        let shard = &*shard_ptr(handle)?;
        let id = get_string(&mut env, &id)?;
        let vector = get_f32_vector(&mut env, &vector)?;
        store::upsert(shard, &id, &vector)
    });
    if let Err(err) = result {
        throw_err(&mut env, err);
    }
}

unsafe extern "system" fn native_delete(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    id: JString,
) {
    let result = guard(|| {
        let shard = &*shard_ptr(handle)?;
        let id = get_string(&mut env, &id)?;
        store::delete(shard, &id)
    });
    if let Err(err) = result {
        throw_err(&mut env, err);
    }
}

unsafe extern "system" fn native_search<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    vector: JFloatArray<'local>,
    limit: jint,
) -> JObjectArray<'local> {
    let result = guard(|| {
        let shard = &*shard_ptr(handle)?;
        let query = get_f32_vector(&mut env, &vector)?;
        let scored = store::search(shard, &query, limit.max(1) as usize)?;
        let class = env.find_class(CLASS_SEARCH_RESULT).map_err(map_jni)?;
        let mut array = env
            .new_object_array(scored.len() as jint, class, JObject::null())
            .map_err(map_jni)?;
        for (index, point) in scored.iter().enumerate() {
            let obj = build_search_result(&mut env, &point.id.to_string(), point.score as f64)?;
            env.set_object_array_element(&mut array, index as jint, obj)
                .map_err(map_jni)?;
        }
        Ok(array)
    });
    match result {
        Ok(array) => array,
        Err(err) => {
            throw_err(&mut env, err);
            JObjectArray::default()
        }
    }
}

unsafe extern "system" fn native_count(mut env: JNIEnv, _class: JClass, handle: jlong) -> jlong {
    let result = guard(|| {
        let shard = &*shard_ptr(handle)?;
        Ok(store::count(shard)? as jlong)
    });
    match result {
        Ok(count) => count,
        Err(err) => {
            throw_err(&mut env, err);
            0
        }
    }
}

unsafe extern "system" fn native_optimize(mut env: JNIEnv, _class: JClass, handle: jlong) {
    let result = guard(|| {
        let shard = &*shard_ptr(handle)?;
        store::optimize(shard)
    });
    if let Err(err) = result {
        throw_err(&mut env, err);
    }
}

unsafe extern "system" fn native_close(mut env: JNIEnv, _class: JClass, handle: jlong) {
    let result = guard(|| {
        let ptr = shard_ptr(handle)?;
        drop(Box::from_raw(ptr));
        Ok(())
    });
    if let Err(err) = result {
        throw_err(&mut env, err);
    }
}

fn jni_methods() -> Vec<jni::NativeMethod> {
    let mut methods = Vec::new();
    for (name, signature, function) in [
        ("nativeCreate", "(Ljava/lang/String;I)J", native_create as *const () as usize),
        ("nativeOpen", "(Ljava/lang/String;)J", native_open as *const () as usize),
        (
            "nativeUpsert",
            "(JLjava/lang/String;[F)V",
            native_upsert as *const () as usize,
        ),
        (
            "nativeDelete",
            "(JLjava/lang/String;)V",
            native_delete as *const () as usize,
        ),
        (
            "nativeSearch",
            "(J[FI)[Lcom/example/EdgeMemo/native/qdrant/SearchResult;",
            native_search as *const () as usize,
        ),
        ("nativeCount", "(J)J", native_count as *const () as usize),
        (
            "nativeOptimize",
            "(J)V",
            native_optimize as *const () as usize,
        ),
        ("nativeClose", "(J)V", native_close as *const () as usize),
    ] {
        methods.push(jni::NativeMethod {
            name: name.into(),
            sig: signature.into(),
            fn_ptr: function as *mut c_void,
        });
    }
    methods
}

#[no_mangle]
pub extern "system" fn JNI_OnLoad(vm: JavaVM, _reserved: *mut c_void) -> jint {
    let methods = jni_methods();
    let mut env = match vm.attach_current_thread_permanently() {
        Ok(env) => env,
        Err(_) => return JNI_VERSION_1_6,
    };
    let _ = env.register_native_methods(CLASS_NATIVE_BRIDGE, &methods);
    JNI_VERSION_1_6
}