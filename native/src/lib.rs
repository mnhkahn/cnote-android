//! Small, versioned JSON command/event ABI. No raw Rust pointers cross JNI.
mod migration;
use anyhow::{Context, Result};
use jni::{
    JNIEnv, JavaVM,
    objects::{GlobalRef, JObject, JString, JValue},
    sys::jstring,
};
use shared_core::{Session, credential_store::Vault};
use std::{
    path::PathBuf,
    sync::{Arc, OnceLock},
};
static SESSION: OnceLock<Session> = OnceLock::new();
struct AndroidVault {
    vm: JavaVM,
    object: GlobalRef,
}
impl Vault for AndroidVault {
    fn get(&self, key: &str) -> Result<Option<String>> {
        let mut env = self.vm.attach_current_thread()?;
        let key = JObject::from(env.new_string(key)?);
        let value = env
            .call_method(
                self.object.as_obj(),
                "get",
                "(Ljava/lang/String;)Ljava/lang/String;",
                &[JValue::Object(&key)],
            )?
            .l()?;
        if value.is_null() {
            return Ok(None);
        }
        Ok(Some(env.get_string(&JString::from(value))?.into()))
    }
    fn set(&self, key: &str, value: Option<&str>) -> Result<()> {
        let mut env = self.vm.attach_current_thread()?;
        let key = JObject::from(env.new_string(key)?);
        let value = match value {
            Some(s) => JObject::from(env.new_string(s)?),
            None => JObject::null(),
        };
        env.call_method(
            self.object.as_obj(),
            "set",
            "(Ljava/lang/String;Ljava/lang/String;)V",
            &[JValue::Object(&key), JValue::Object(&value)],
        )?;
        Ok(())
    }
}
fn boundary<T>(
    env: &mut JNIEnv<'_>,
    operation: impl FnOnce(&mut JNIEnv<'_>) -> Result<T>,
) -> Option<T> {
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| operation(env))) {
        Ok(Ok(value)) => Some(value),
        result => {
            let message = match result {
                Ok(Err(error)) => format!("{error:#}"),
                _ => "共享核心发生异常，请重启应用".into(),
            };
            if !env.exception_check().unwrap_or(true) {
                let _ = env.throw_new("java/lang/IllegalStateException", message);
            }
            None
        }
    }
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_cyeam_cnote_sdk_NativeCore_open(
    mut env: JNIEnv,
    _: JObject,
    path: JString,
    legacy: JString,
    downloads: JString,
    vault: JObject,
) {
    boundary(&mut env, |env| {
        if SESSION.get().is_some() {
            return Ok(());
        }
        let path: String = env.get_string(&path)?.into();
        let legacy: String = env.get_string(&legacy)?.into();
        let downloads: String = env.get_string(&downloads)?.into();
        let vault = Arc::new(AndroidVault {
            vm: env.get_java_vm()?,
            object: env.new_global_ref(vault)?,
        });
        shared_core::http::set_client_factory(http_client)?;
        std::fs::create_dir_all(PathBuf::from(&path).parent().context("数据库目录无效")?)?;
        migration::import_android(std::path::Path::new(&path), std::path::Path::new(&legacy))?;
        let session = Session::open(PathBuf::from(path), PathBuf::from(downloads), vault)?;
        SESSION
            .set(session)
            .map_err(|_| anyhow::anyhow!("共享核心已打开"))?;
        Ok(())
    });
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_cyeam_cnote_sdk_NativeCore_dispatch(
    mut env: JNIEnv,
    _: JObject,
    raw: JString,
) {
    boundary(&mut env, |env| {
        let raw: String = env.get_string(&raw)?.into();
        SESSION.get().context("共享核心尚未打开")?.dispatch(&raw)
    });
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_cyeam_cnote_sdk_NativeCore_poll(
    mut env: JNIEnv,
    _: JObject,
) -> jstring {
    boundary(&mut env, |env| {
        let events = SESSION.get().context("共享核心尚未打开")?.poll()?;
        Ok(env.new_string(events.to_string())?.into_raw())
    })
    .unwrap_or(std::ptr::null_mut())
}

fn http_client() -> reqwest::blocking::ClientBuilder {
    let roots = rustls::RootCertStore {
        roots: webpki_roots::TLS_SERVER_ROOTS.to_vec(),
    };
    let tls = rustls::ClientConfig::builder()
        .with_root_certificates(roots)
        .with_no_client_auth();
    reqwest::blocking::Client::builder().tls_backend_preconfigured(tls)
}
