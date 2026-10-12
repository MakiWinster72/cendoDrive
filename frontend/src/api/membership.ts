import http from "./http";
import type { UserResponse } from "./auth";

export interface SimulatedPaymentResponse {
  amount: string;
  paymentMethod: "wechat";
  simulated: boolean;
  user: UserResponse;
}
export async function simulateMembershipPayment(planId: string) {
  return (
    await http.post<SimulatedPaymentResponse>("/membership/simulate-payment", {
      planId,
      paymentMethod: "wechat",
    })
  ).data;
}
