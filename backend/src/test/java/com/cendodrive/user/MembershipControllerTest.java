package com.cendodrive.user;

import com.cendodrive.common.ApiExceptionHandler.DriveFailure;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MembershipControllerTest {
  @Test void disabledDemoRejectsWithoutUpdatingUser() {
    UserRepository users = mock(UserRepository.class);
    MembershipController controller = new MembershipController(users, false);
    DriveFailure failure = assertThrows(DriveFailure.class, () -> controller.pay(mock(User.class),
        new MembershipController.PaymentRequest("year", "wechat")));
    assertEquals("SIMULATED_PAYMENT_DISABLED", failure.code());
    verifyNoInteractions(users);
  }
}
