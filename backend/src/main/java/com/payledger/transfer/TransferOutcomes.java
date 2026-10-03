package com.payledger.transfer;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

/**
 * Maps a recorded transfer to its HTTP response. A FAILED transfer is a 422 problem carrying the stable
 * failure {@code code} and the {@code transferId}, so the client can branch on the code and still look the
 * rejected attempt up later.
 */
final class TransferOutcomes {

    private TransferOutcomes() {
    }

    static ResponseEntity<Object> toResponse(Transfer transfer) {
        if (transfer.getStatus() == TransferStatus.FAILED) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT,
                    transfer.getFailureReason());
            problem.setProperty("code", transfer.getFailureCode());
            problem.setProperty("transferId", transfer.getId());
            return ResponseEntity.unprocessableContent().body(problem);
        }
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/transfers/{id}").buildAndExpand(transfer.getId()).toUri();
        return ResponseEntity.created(location).body(TransferResponse.from(transfer));
    }
}
