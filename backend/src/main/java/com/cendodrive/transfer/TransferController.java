package com.cendodrive.transfer;

import com.cendodrive.transfer.TransferDtos.*;
import com.cendodrive.user.User;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {
  private final TransferService transfers;

  public TransferController(TransferService transfers) {
    this.transfers = transfers;
  }

  @GetMapping
  List<TransferResponse> list(@AuthenticationPrincipal User user) {
    return transfers.list(user);
  }

  @PostMapping
  TransferResponse save(@AuthenticationPrincipal User user, @Valid @RequestBody SaveRequest request) {
    return transfers.save(user, request);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(@AuthenticationPrincipal User user, @PathVariable String id) {
    transfers.delete(user, id);
  }

  @DeleteMapping
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void clear(@AuthenticationPrincipal User user, @RequestParam String direction) {
    transfers.clear(user, direction);
  }
}
