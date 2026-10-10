package com.cendodrive.ai.claw;

import com.cendodrive.drive.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ClawFileAccess {
  private final DriveFileRepository files;
  public ClawFileAccess(DriveFileRepository files) { this.files=files; }
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
