package com.cendodrive.drive;

import com.cendodrive.common.ApiExceptionHandler.DriveFailure;
import com.cendodrive.drive.DriveDtos.FileResponse;
import com.cendodrive.drive.FileSearchDtos.*;
import com.cendodrive.user.User;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FileSearchService {
  // Walk from owned, visible roots: hidden/deleted ancestors, orphaned nodes and
  // cycles never enter the result.
  private static final String VISIBLE = """
      WITH RECURSIVE visible (id, parent_id, kind, in_scope) AS (
        SELECT id, parent_id, kind, CASE WHEN id = :scopeId THEN 1 ELSE 0 END
        FROM drive_files WHERE owner_id = :owner AND parent_id IS NULL AND deleted_at IS NULL AND hidden = FALSE
        UNION ALL
        SELECT child.id, child.parent_id, child.kind,
          CASE WHEN parent.in_scope = 1 OR child.id = :scopeId THEN 1 ELSE 0 END
        FROM drive_files child JOIN visible parent ON child.parent_id = parent.id AND parent.kind = 'folder'
        WHERE child.owner_id = :owner AND child.deleted_at IS NULL AND child.hidden = FALSE
      )
      """;
  // Keep these extensions consistent with frontend/src/components/fileIcon.ts.
  private static final Map<String, List<String>> EXTENSIONS = Map.of(
      "image", List.of("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic", "avif"),
      "video", List.of("mp4", "mov", "avi", "mkv", "webm", "m4v"),
      "audio", List.of("mp3", "wav", "flac", "aac", "ogg", "m4a", "wma"),
      "doc", List.of("pdf", "doc", "docx", "odt", "rtf", "ppt", "pptx", "odp", "xls", "xlsx", "csv", "ods", "txt",
          "log", "md", "markdown", "mdx"));
  private final NamedParameterJdbcTemplate jdbc;
  private final DriveService drive;

  public FileSearchService(NamedParameterJdbcTemplate jdbc, DriveService drive) {
    this.jdbc = jdbc;
    this.drive = drive;
  }

  @Transactional(readOnly = true)
  public SearchResponse search(User user, String rawQuery, String scope, Long parentId, String type,
      String sort, int page, int size) {
    String query = rawQuery == null ? "" : rawQuery.strip();
    if (query.isEmpty() || query.length() > 100 || page < 0 || page > 10000 || size < 1 || size > 100
        || !Set.of("all", "folder").contains(scope)
        || !Set.of("all", "folder", "image", "video", "audio", "doc", "other").contains(type)
        || !Set.of("time", "name", "size").contains(sort) || (scope.equals("all") && parentId != null))
      throw new DriveFailure(HttpStatus.BAD_REQUEST, "INVALID_SEARCH", "Invalid search parameters");
    if (parentId != null) {
      DriveFile folder = drive.requireFolder(user, parentId);
      if (drive.hiddenTree(user, folder))
        throw new DriveFailure(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND", "File not found");
    }
    // '!' is an explicit LIKE escape: %, _ and ! in filenames remain literal; all
    // values are bound.
    String escaped = query.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_");
    var params = new MapSqlParameterSource().addValue("owner", user.getId()).addValue("scopeId", parentId)
        .addValue("q", "%" + escaped + "%").addValue("limit", size).addValue("offset", (long) page * size);
    String filter = " FROM drive_files f JOIN visible v ON f.id = v.id WHERE LOWER(f.name) LIKE :q ESCAPE '!'"
        + (parentId == null ? "" : " AND v.in_scope = 1 AND f.id <> :scopeId") + typeFilter(type);
    long total = Objects.requireNonNull(jdbc.queryForObject(VISIBLE + "SELECT COUNT(*)" + filter, params, Long.class));
    String order = switch (sort) {
      case "name" -> "f.name ASC, f.id ASC";
      case "size" -> "f.size_bytes DESC, f.id DESC";
      default -> "f.updated_at DESC, f.id DESC";
    };
    List<FileResponse> files = jdbc.query(
        VISIBLE + "SELECT f.*" + filter + " ORDER BY " + order + " LIMIT :limit OFFSET :offset", params,
        (rs, row) -> new FileResponse(rs.getString("id"), rs.getString("name"), rs.getString("kind"),
            rs.getLong("size_bytes"),
            rs.getString("parent_id"),
            rs.getTimestamp("updated_at").toLocalDateTime().atOffset(ZoneOffset.UTC).toInstant().toString(),
            null, rs.getBoolean("favorite"), false));
    Map<Long, FileResponse> parents = new HashMap<>();
    List<SearchHit> hits = files.stream().map(file -> {
      LinkedList<FileResponse> ancestors = new LinkedList<>();
      Long id = file.parentId() == null ? null : Long.valueOf(file.parentId());
      Set<Long> seen = new HashSet<>();
      while (id != null && seen.add(id)) {
        FileResponse parent = parents.computeIfAbsent(id, key -> FileResponse.from(drive.requireOwned(user, key)));
        ancestors.addFirst(parent);
        id = parent.parentId() == null ? null : Long.valueOf(parent.parentId());
      }
      return new SearchHit(file, List.copyOf(ancestors),
          "/" + String.join("/", ancestors.stream().map(FileResponse::name).toList()));
    }).toList();
    return new SearchResponse(hits, total, page, size);
  }

  private static String suffixFilter(Collection<String> extensions) {
    return "(" + String.join(" OR ", extensions.stream().map(ext -> "LOWER(f.name) LIKE '%." + ext + "'").toList())
        + ")";
  }

  private static String typeFilter(String type) {
    if (type.equals("all"))
      return "";
    if (type.equals("folder"))
      return " AND f.kind = 'folder'";
    String extensions = type.equals("other")
        ? "NOT " + suffixFilter(EXTENSIONS.values().stream().flatMap(Collection::stream).toList())
        : suffixFilter(EXTENSIONS.get(type));
    return " AND f.kind = 'file' AND " + extensions;
  }
}
