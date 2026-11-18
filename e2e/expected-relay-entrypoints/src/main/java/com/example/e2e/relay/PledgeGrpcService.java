package com.example.e2e.relay;

import com.example.proto.PledgeReply;
import com.example.proto.PledgeRequest;
import com.example.proto.PledgeServiceGrpc;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.Executor;
import org.springframework.stereotype.Service;

/** A gRPC service: one call answered before the method returns, one answered later on another thread. */
@Service
public class PledgeGrpcService extends PledgeServiceGrpc.PledgeServiceImplBase {
    private final RelayLog relayLog;
    private final Executor relayExecutor;

    PledgeGrpcService(RelayLog relayLog, Executor relayExecutor) {
        this.relayLog = relayLog;
        this.relayExecutor = relayExecutor;
    }

    @Override
    public void lookup(PledgeRequest request, StreamObserver<PledgeReply> responseObserver) {
        String pledgeId = request.getPledgeId();
        relayLog.record(pledgeId);
        responseObserver.onNext(PledgeReply.of("found"));
        responseObserver.onCompleted();
    }

    @Override
    public void watch(PledgeRequest request, StreamObserver<PledgeReply> responseObserver) {
        String pledgeId = request.getPledgeId();
        relayLog.record(pledgeId);
        relayExecutor.execute(() -> {
            responseObserver.onNext(PledgeReply.of("changed"));
            responseObserver.onCompleted();
        });
    }

    @Override
    public StreamObserver<PledgeRequest> upload(StreamObserver<PledgeReply> responseObserver) {
        return new StreamObserver<>() {
            @Override
            public void onNext(PledgeRequest value) {
                relayLog.record(value.getPledgeId());
            }

            @Override
            public void onError(Throwable t) {
                responseObserver.onError(t);
            }

            @Override
            public void onCompleted() {
                responseObserver.onCompleted();
            }
        };
    }
}
