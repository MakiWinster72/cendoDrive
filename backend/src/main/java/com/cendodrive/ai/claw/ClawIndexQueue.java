package com.cendodrive.ai.claw;

import com.cendodrive.drive.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class ClawIndexQueue {
  public record Document(long fileId,long ownerId,long generation,String collection,String status,int chunks,String errorCode) {}
  public record Task(long id,long ownerId,long fileId,long generation,String collection,String operation,int attempts,String lease) {}
  private final JdbcTemplate jdbc;
  private final ClawSettings settings;
  private final DriveFileRepository files;
  private final ClawFileAccess access;
  public ClawIndexQueue(JdbcTemplate jdbc,ClawSettings settings,DriveFileRepository files,ClawFileAccess access) {
    this.jdbc=jdbc;this.settings=settings;this.files=files;this.access=access;
  }
  private static LocalDateTime now() { return LocalDateTime.now(Clock.systemUTC()); }
  private static final String DUE_TASK="(status IN ('PENDING','RETRY','PROCESSING') OR (operation='DELETE' AND status='DEAD')) AND next_attempt_at<=? AND (operation='DELETE' OR collection_name=?)";
  private void lockFile(long id) {
    // All document mutators acquire file -> document/task, never task -> file.
    jdbc.queryForList("SELECT id FROM drive_files WHERE id=? FOR UPDATE",Long.class,id);
  }
  @Transactional(readOnly=true) public List<Document> documents(long owner) {
    return jdbc.query("SELECT * FROM claw_documents WHERE owner_id=? ORDER BY file_id DESC",(row,n)->new Document(row.getLong("file_id"),row.getLong("owner_id"),row.getLong("generation"),row.getString("collection_name"),row.getString("status"),row.getInt("chunks"),row.getString("error_code")),owner);
  }
  @Transactional(readOnly=true) public Set<Long> retrying(long owner) {
    return new HashSet<>(jdbc.queryForList("SELECT t.file_id FROM claw_tasks t JOIN claw_documents d ON d.file_id=t.file_id AND d.generation=t.generation AND d.collection_name=t.collection_name WHERE t.owner_id=? AND t.operation='UPSERT' AND t.status IN ('PENDING','RETRY','PROCESSING') AND t.collection_name=?",Long.class,owner,settings.collection()));
  }
  @Transactional(propagation=Propagation.MANDATORY) public void enqueue(DriveFile file) {
    if(!settings.enabled() || file.isFolder()) return;
    lockFile(file.getId());
    // Materialize the key before a current read: missing-key FOR UPDATE locks compatible
    // gaps under MySQL RR, then concurrent first inserts can deadlock across owners.
    // Generation zero is transaction-local and must never produce a deletion task.
    jdbc.update("INSERT INTO claw_documents(file_id,owner_id,generation,collection_name,status,chunks,error_code,updated_at) VALUES(?,?,0,?,'PENDING',0,NULL,?) ON DUPLICATE KEY UPDATE file_id=VALUES(file_id)",file.getId(),file.getOwnerId(),settings.collection(),now());
    var previous=jdbc.query("SELECT * FROM claw_documents WHERE file_id=? FOR UPDATE",(row,n)->new Document(row.getLong("file_id"),row.getLong("owner_id"),row.getLong("generation"),row.getString("collection_name"),row.getString("status"),row.getInt("chunks"),row.getString("error_code")),file.getId()).get(0);
    long generation=previous.generation()+1;
    if(previous.generation()>0) addDelete(previous,previous.generation());
    String error=!ClawDocumentReader.supports(file.getName())?"CLAW_UNSUPPORTED":file.getSize()>ClawDocumentReader.MAX_BYTES?"CLAW_TOO_LARGE":null;
    String state=error==null?"PENDING":"UNSUPPORTED";
    jdbc.update("UPDATE claw_documents SET generation=?,collection_name=?,status=?,chunks=0,error_code=?,updated_at=? WHERE file_id=?",generation,settings.collection(),state,error,now(),file.getId());
    if(error==null) jdbc.update("INSERT INTO claw_tasks(owner_id,file_id,generation,collection_name,operation,status,next_attempt_at,created_at) VALUES(?,?,?,?,'UPSERT','PENDING',?,?)",file.getOwnerId(),file.getId(),generation,settings.collection(),now(),now());
    // Delete one complete unique key after inserting its successor, not an empty
    // file-wide task range. PROCESSING predecessors retain their lease and cleanup.
    if(previous.generation()>0) jdbc.update("DELETE FROM claw_tasks WHERE file_id=? AND generation=? AND collection_name=? AND operation='UPSERT' AND status<>'PROCESSING'",file.getId(),previous.generation(),previous.collection());
  }
  @Transactional(propagation=Propagation.MANDATORY) public void changed(DriveFile root) {
    if(!settings.enabled()) return;
    var all=files.findAllByOwnerId(root.getOwnerId()); var ids=new HashSet<Long>();ids.add(root.getId());
    boolean added;
    do { added=false;for(var file:all) if(file.getParentId()!=null && ids.contains(file.getParentId())) added|=ids.add(file.getId()); } while(added);
    for(var file:all.stream().sorted(Comparator.comparing(DriveFile::getId)).toList()) {
      if(file.isFolder() || !ids.contains(file.getId())) continue;
      lockFile(file.getId());
      // Test current eligibility AFTER the metadata fence, not via an earlier RR owner snapshot.
      var tracked=jdbc.queryForList("SELECT file_id FROM claw_documents WHERE file_id=? FOR UPDATE",Long.class,file.getId());
      if(!tracked.isEmpty()) enqueue(file);
    }
  }
  @Transactional(propagation=Propagation.MANDATORY) public void removed(DriveFile file) {
    lockFile(file.getId());
    var documents=jdbc.query("SELECT * FROM claw_documents WHERE file_id=? FOR UPDATE",(row,n)->new Document(row.getLong("file_id"),row.getLong("owner_id"),row.getLong("generation"),row.getString("collection_name"),row.getString("status"),row.getInt("chunks"),row.getString("error_code")),file.getId());
    for(var document:documents) addDelete(document,0);
    jdbc.update("DELETE FROM claw_documents WHERE file_id=?",file.getId());
  }
  private void addDelete(Document document,long generation) {
    // Invalidate an in-flight DELETE lease so a late writer always gets a later cleanup pass.
    jdbc.update("INSERT INTO claw_tasks(owner_id,file_id,generation,collection_name,operation,status,next_attempt_at,created_at) VALUES(?,?,?,?,'DELETE','PENDING',?,?) ON DUPLICATE KEY UPDATE status='PENDING',next_attempt_at=?,lease_token=NULL,attempts=0",document.ownerId(),document.fileId(),generation,document.collection(),now(),now(),now());
  }
  @Transactional public void cleanupIfObsolete(Task task) {
    if(!"UPSERT".equals(task.operation())) return;
    lockFile(task.fileId());
    if(current(task) && access.visible(task.ownerId(),task.fileId())!=null) return;
    addDelete(new Document(task.fileId(),task.ownerId(),task.generation(),task.collection(),"",0,null),task.generation());
  }
  @Transactional public Task claim() {
    if(!settings.enabled()) return null;
    var candidates=jdbc.queryForList("SELECT id,file_id FROM claw_tasks WHERE "+DUE_TASK+" ORDER BY id LIMIT 1",now(),settings.collection());
    if(candidates.isEmpty()) return null;
    long id=((Number)candidates.get(0).get("id")).longValue();
    lockFile(((Number)candidates.get(0).get("file_id")).longValue());
    // Recheck eligibility using a current read after waiting on the file fence.
    var due=jdbc.queryForList("SELECT id FROM claw_tasks WHERE "+DUE_TASK+" AND id=? FOR UPDATE",Long.class,now(),settings.collection(),id);
    if(due.isEmpty()) return null;
    String lease=UUID.randomUUID().toString();
    jdbc.update("UPDATE claw_tasks SET status='PROCESSING',lease_token=?,next_attempt_at=?,attempts=attempts+1 WHERE id=?",lease,now().plusMinutes(30),id);
    var task=jdbc.queryForObject("SELECT * FROM claw_tasks WHERE id=? FOR UPDATE",(row,n)->new Task(row.getLong("id"),row.getLong("owner_id"),row.getLong("file_id"),row.getLong("generation"),row.getString("collection_name"),row.getString("operation"),row.getInt("attempts"),row.getString("lease_token")),id);
    if("UPSERT".equals(task.operation())) jdbc.update("UPDATE claw_documents SET status='PROCESSING',updated_at=? WHERE file_id=? AND generation=? AND collection_name=?",now(),task.fileId(),task.generation(),task.collection());
    return task;
  }
  @Transactional(readOnly=true) public boolean current(Task task) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM claw_documents WHERE file_id=? AND generation=? AND collection_name=?",Integer.class,task.fileId(),task.generation(),task.collection())==1;
  }
  @Transactional public boolean publish(Task task,int chunks) {
    lockFile(task.fileId());
    if(!ownsLease(task) || !current(task) || access.visible(task.ownerId(),task.fileId())==null) return false;
    return jdbc.update("UPDATE claw_documents SET status='READY',chunks=?,error_code=NULL,updated_at=? WHERE file_id=? AND generation=? AND collection_name=?",chunks,now(),task.fileId(),task.generation(),task.collection())==1;
  }
  private boolean ownsLease(Task task) {
    var leases=jdbc.queryForList("SELECT lease_token FROM claw_tasks WHERE id=? FOR UPDATE",String.class,task.id());
    return !leases.isEmpty() && Objects.equals(leases.get(0),task.lease());
  }
  @Transactional public void finish(Task task) { jdbc.update("DELETE FROM claw_tasks WHERE id=? AND lease_token=?",task.id(),task.lease()); }
  @Transactional public void failed(Task task,String code,boolean permanent) {
    lockFile(task.fileId());
    if(!ownsLease(task)) return;
    if("UPSERT".equals(task.operation())) jdbc.update("UPDATE claw_documents SET status=?,error_code=?,updated_at=? WHERE file_id=? AND generation=? AND collection_name=?",code.equals("CLAW_FILE_UNAVAILABLE")?"SKIPPED":"FAILED",code,now(),task.fileId(),task.generation(),task.collection());
    if(permanent && "UPSERT".equals(task.operation())) { finish(task);return; }
    jdbc.update("UPDATE claw_tasks SET status=?,next_attempt_at=?,lease_token=NULL WHERE id=? AND lease_token=?",task.attempts()>=5 && "UPSERT".equals(task.operation())?"DEAD":"RETRY",now().plusSeconds(Math.min(3600,30L<<Math.min(task.attempts(),6))),task.id(),task.lease());
  }
}
