package com.cendodrive.transfer;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "transfer_records", uniqueConstraints = @UniqueConstraint(name = "uk_transfer_owner_client", columnNames = {
    "owner_id", "client_id" }))
public class TransferRecord {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @Column(name = "owner_id", nullable = false)
  private Long ownerId;
  @Column(name = "client_id", nullable = false, length = 512)
  private String clientId;
  @Column(nullable = false, length = 16)
  private String direction;
  @Column(nullable = false, length = 255)
  private String name;
  @Column(name = "size_bytes", nullable = false)
  private long size;
  @Column(nullable = false, length = 16)
  private String status;
  @Column(nullable = false)
  private int progress;
  @Column(length = 1000)
  private String error;
  @Column(name = "file_id")
  private Long fileId;
  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  protected TransferRecord() {
  }

  TransferRecord(Long ownerId, TransferDtos.SaveRequest request) {
    this.ownerId = ownerId;
    this.clientId = request.id();
    this.createdAt = LocalDateTime.ofInstant(Instant.ofEpochMilli(request.createdAt()), ZoneOffset.UTC);
    update(request);
  }

  void update(TransferDtos.SaveRequest request) {
    direction = request.direction();
    name = request.name();
    size = request.size();
    status = request.status();
    progress = request.progress();
    error = request.error();
    if (request.fileId() != null)
      fileId = request.fileId();
  }

  TransferDtos.TransferResponse response() {
    return new TransferDtos.TransferResponse(clientId, direction, name, size, status, progress,
        createdAt.toInstant(ZoneOffset.UTC).toEpochMilli(), error, fileId == null ? null : fileId.toString());
  }
}
