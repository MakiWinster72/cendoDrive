package com.cendodrive.ai.claw;

import com.cendodrive.drive.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ClawFileAccess {
  private final DriveFileRepository files;
  private final org.springframework.jdbc.core.JdbcTemplate jdbc;
  public ClawFileAccess(DriveFileRepository files,org.springframework.jdbc.core.JdbcTemplate jdbc) { this.files=files;this.jdbc=jdbc; }
  public record FileInfo(long id,Long parentId,String name,String kind,boolean hidden,boolean deleted) {
    boolean folder() { return "folder".equals(kind); }
  }
  public record Snapshot(Map<Long,FileInfo> all,Map<Long,FileInfo> visible) {
    public String path(long id) {
      var parts=new ArrayList<String>();var cursor=visible.get(id);
      for(int depth=0;cursor!=null && depth<256;depth++) {parts.add(cursor.name());cursor=cursor.parentId()==null?null:all.get(cursor.parentId());}
      Collections.reverse(parts);String path="/"+String.join("/",parts);
      // Keep original metadata for post-inference validation; only model evidence may be shortened.
      return path;
    }
  }
  public Snapshot snapshot(long owner) {
    // Fresh JDBC reads deliberately bypass the JPA identity map, including with open-in-view enabled.
    var all=new HashMap<Long,FileInfo>();
    jdbc.query("SELECT id,parent_id,name,kind,hidden,deleted_at FROM drive_files WHERE owner_id=?",row->{
      long parent=row.getLong("parent_id");Long parentId=row.wasNull()?null:parent;
      var file=new FileInfo(row.getLong("id"),parentId,row.getString("name"),row.getString("kind"),row.getBoolean("hidden"),row.getTimestamp("deleted_at")!=null);
      all.put(file.id(),file);
    },owner);
    var visible=new LinkedHashMap<Long,FileInfo>();
    for(var file:all.values()) {
      if(!"file".equals(file.kind())) continue;
      var visited=new HashSet<Long>();var cursor=file;
      for(int depth=0;cursor!=null && depth<256;depth++) {
        if(cursor.hidden() || cursor.deleted() || !visited.add(cursor.id())) break;
        if(cursor.parentId()==null) {visible.put(file.id(),file);break;}
        cursor=all.get(cursor.parentId());if(cursor==null || !cursor.folder()) break;
      }
    }
    return new Snapshot(Map.copyOf(all),Map.copyOf(visible));
  }

  public DriveFile visible(long owner,long id) {
    DriveFile file=files.findByIdAndOwnerId(id,owner).orElse(null), cursor=file;
    var visited=new HashSet<Long>();
    for(int depth=0;cursor!=null && depth<256;depth++) {
      if(cursor.isDeleted() || cursor.isHidden() || !Objects.equals(cursor.getOwnerId(),owner) || !visited.add(cursor.getId())) return null;
      if(cursor.getParentId()==null) return file!=null && !file.isFolder()?file:null;
      cursor=files.findByIdAndOwnerId(cursor.getParentId(),owner).orElse(null);
      if(cursor==null || !cursor.isFolder()) return null;
    }
    return null;
  }
  public Map<Long,DriveFile> visibleFiles(long owner) {
    var all=new HashMap<Long,DriveFile>(); files.findAllByOwnerId(owner).forEach(f->all.put(f.getId(),f));
    var result=new LinkedHashMap<Long,DriveFile>();
    for(var file:all.values()) {
      if(file.isFolder()) continue;
      var visited=new HashSet<Long>(); DriveFile cursor=file;
      for(int depth=0;cursor!=null && depth<256;depth++) {
        if(cursor.isHidden() || cursor.isDeleted() || !Objects.equals(cursor.getOwnerId(),owner) || !visited.add(cursor.getId())) break;
        if(cursor.getParentId()==null) { result.put(file.getId(),file); break; }
        cursor=all.get(cursor.getParentId()); if(cursor==null || !cursor.isFolder()) break;
      }
    }
    return result;
  }
}
