package com.cendodrive.transfer;

import com.cendodrive.transfer.TransferDtos.*;
import com.cendodrive.common.ApiExceptionHandler.DriveFailure;
import com.cendodrive.user.User;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {
  private final TransferRecordRepository records;
  public TransferService(TransferRecordRepository records) { this.records = records; }
  @Transactional(readOnly = true)
  public List<TransferResponse> list(User user) {
    return records.findAllByOwnerIdOrderByCreatedAtAscIdAsc(user.getId()).stream().map(TransferRecord::response).toList();
  }
  @Transactional
  public TransferResponse save(User user, SaveRequest request) {
    TransferRecord record = records.findByOwnerIdAndClientId(user.getId(), request.id())
        .orElseGet(() -> new TransferRecord(user.getId(), request));
    record.update(request);
    return records.save(record).response();
  }
  @Transactional
  public void delete(User user, String id) { records.deleteByOwnerIdAndClientId(user.getId(), id); }
  @Transactional
  public void clear(User user, String direction) {
    if (!List.of("upload", "download", "transfer").contains(direction))
      throw new DriveFailure(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "Invalid direction");
    records.deleteAllByOwnerIdAndDirection(user.getId(), direction);
  }
}
