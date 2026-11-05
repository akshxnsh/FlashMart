package com.flashmart.order;

import com.flashmart.order.client.payment.InitiatePaymentRequest;
import com.flashmart.order.client.payment.InitiatePaymentResponse;
import com.flashmart.order.client.payment.PaymentGatewayClient;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * For tests whose subject is not the payment step: a mocked gateway that
 * accepts every initiate. The ref is {@code "pay_" + Idempotency-Key}, so the
 * same key always gets the same payment, as a gateway honouring RZP-1 would
 * (an assumption of the mock, not a fact about any gateway).
 */
public final class TestPayments {

    private TestPayments() {
    }

    public static void acceptEverything(PaymentGatewayClient gateway) {
        given(gateway.initiate(anyString(), any())).willAnswer(inv -> {
            InitiatePaymentRequest request = inv.getArgument(1);
            return new InitiatePaymentResponse("pay_" + inv.getArgument(0), request.orderId(), request.amount(),
                    "PENDING");
        });
    }
}
