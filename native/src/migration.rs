//! One-time, transactional import. The legacy Android database is read-only.
use anyhow::{Context, Result};
use rusqlite::{Connection, params};
use std::path::Path;
pub fn import_android(target: &Path, legacy: &Path) -> Result<()> {
    drop(shared_core::store::Store::open(target)?);
    let mut db = Connection::open(target)?;
    let done: bool = db.query_row(
        "SELECT EXISTS(SELECT 1 FROM settings WHERE key='android_import_v1')",
        [],
        |r| r.get(0),
    )?;
    if done {
        return Ok(());
    }
    if legacy.is_file() {
        let mut uri =
            url::Url::from_file_path(legacy).map_err(|_| anyhow::anyhow!("旧数据库路径无效"))?;
        uri.query_pairs_mut().append_pair("mode", "ro");
        db.execute("ATTACH DATABASE ?1 AS legacy", [uri.as_str()])?;
        let tx = db.transaction()?;
        let columns: Vec<String> = {
            let mut q = tx.prepare("PRAGMA legacy.table_info(notes)")?;
            q.query_map([], |r| r.get(1))?
                .collect::<rusqlite::Result<_>>()?
        };
        anyhow::ensure!(
            columns.iter().any(|c| c == "loaded"),
            "旧笔记数据库格式无法识别，未迁移"
        );
        tx.execute_batch("INSERT INTO main.notes(id,account,remote_id,title,html,baseline,etag,revision,dirty,deleted,conflict,modified)
            SELECT id,account,remote_id,title,html,baseline,CASE WHEN loaded=0 THEN NULL ELSE etag END,revision,dirty,deleted,conflict,modified FROM legacy.notes WHERE 1
            ON CONFLICT(id) DO NOTHING;
            INSERT INTO main.settings SELECT key,value FROM legacy.settings WHERE key IN ('account','selected','theme') ON CONFLICT(key) DO NOTHING;
            INSERT INTO main.settings SELECT 'name',value FROM legacy.settings WHERE key='account_name' ON CONFLICT(key) DO NOTHING;
            INSERT INTO mobile_conflicts SELECT id,COALESCE(conflict_html,''),CASE WHEN remote_missing=1 THEN '__cnote_remote_missing__' ELSE conflict_etag END FROM legacy.notes WHERE conflict=1 AND (remote_missing=1 OR (conflict_html IS NOT NULL AND conflict_etag IS NOT NULL)) ON CONFLICT(note_id) DO NOTHING;")?;
        // Propagate SQL/import errors before recording completion.
        tx.execute(
            "INSERT INTO settings(key,value) VALUES ('android_import_v1','1')",
            [],
        )?;
        tx.commit().context("旧笔记迁移失败，原数据未修改")?;
        db.execute_batch("DETACH DATABASE legacy")?;
    } else {
        db.execute(
            "INSERT INTO settings VALUES (?1,?2)",
            params!["android_import_v1", "1"],
        )?;
    }
    // Rebuild heading caches for the imported rows.
    drop(shared_core::store::Store::open(target)?);
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn imports_large_drafts_accounts_and_placeholders_once() {
        let dir = tempfile::tempdir().unwrap();
        let old = dir.path().join("old.db");
        let new = dir.path().join("new.db");
        let db = Connection::open(&old).unwrap();
        db.execute_batch("CREATE TABLE notes(id TEXT,account TEXT,remote_id TEXT,title TEXT,html TEXT,baseline TEXT,etag TEXT,revision INTEGER,dirty INTEGER,deleted INTEGER,conflict INTEGER,modified INTEGER,loaded INTEGER,conflict_html TEXT,conflict_etag TEXT,remote_missing INTEGER DEFAULT 0); CREATE TABLE settings(key TEXT,value TEXT); INSERT INTO settings VALUES('account','alice'),('account_name','Alice');").unwrap();
        let body = format!("<h1>迁移草稿</h1>{}", "x".repeat(3 * 1024 * 1024));
        db.execute("INSERT INTO notes VALUES('draft','alice','remote','file',?1,'baseline','v1',7,1,0,1,42,1,'cloud body','cloud-v2',0)",[&body]).unwrap();
        db.execute_batch(
            "INSERT INTO notes VALUES('placeholder','alice','r2','cloud','','','v2',0,0,0,0,10,0,NULL,NULL,0)",
        )
        .unwrap();
        import_android(&new, &old).unwrap();
        let store = shared_core::store::Store::open(&new).unwrap();
        let note = store.get("draft").unwrap();
        assert_eq!(note.html, body);
        assert_eq!(note.revision, 7);
        assert!(note.conflict);
        assert!(note.dirty);
        assert_eq!(
            store.remote_conflicts().unwrap()["draft"],
            ("cloud body".into(), "cloud-v2".into())
        );
        assert_eq!(store.display_title("draft").unwrap(), "迁移草稿");
        assert!(store.get("placeholder").unwrap().etag.is_none());
        assert_eq!(store.setting("name").unwrap().as_deref(), Some("Alice"));
        assert!(store.list("bob").unwrap().is_empty());
        store.save_html("draft", "alice", "new edit").unwrap();
        import_android(&new, &old).unwrap();
        assert_eq!(store.get("draft").unwrap().html, "new edit");
        assert_eq!(
            db.query_row("SELECT html FROM notes WHERE id='draft'", [], |r| r
                .get::<_, String>(0))
                .unwrap(),
            body
        );
    }
    #[test]
    fn malformed_import_never_marks_success() {
        let dir = tempfile::tempdir().unwrap();
        let old = dir.path().join("old.db");
        let new = dir.path().join("new.db");
        Connection::open(&old)
            .unwrap()
            .execute_batch("CREATE TABLE notes(loaded INTEGER)")
            .unwrap();
        assert!(import_android(&new, &old).is_err());
        assert!(
            shared_core::store::Store::open(&new)
                .unwrap()
                .setting("android_import_v1")
                .unwrap()
                .is_none()
        );
    }
}
