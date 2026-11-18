package com.example.e2e.relay;

import com.example.proto.CouponReply;
import com.example.proto.CouponRequest;
import com.example.proto.CouponServiceGrpc;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.Executor;
import org.springframework.stereotype.Service;

/** A gRPC service: one call answered before the method returns, one answered later on another thread. */
@Service
public class CouponGrpcService extends CouponServiceGrpc.CouponServiceImplBase {
    private final RelayLog relayLog;
    private final Executor relayExecutor;

    CouponGrpcService(RelayLog relayLog, Executor relayExecutor) {
        this.relayLog = relayLog;
        this.relayExecutor = relayExecutor;
    }

    @Override
    public void lookup(CouponRequest request, StreamObserver<CouponReply> responseObserver) {
        String couponId = request.getCouponId();
        relayLog.record(couponId);
        responseObserver.onNext(CouponReply.of("found"));
        responseObserver.onCompleted();
    }

    @Override
    public void watch(CouponRequest request, StreamObserver<CouponReply> responseObserver) {
        String couponId = request.getCouponId();
        relayLog.record(couponId);
        relayExecutor.execute(() -> {
            responseObserver.onNext(CouponReply.of("changed"));
            responseObserver.onCompleted();
        });
    }

    @Override
    public StreamObserver<CouponRequest> upload(StreamObserver<CouponReply> responseObserver) {
        return new StreamObserver<>() {
            @Override
            public void onNext(CouponRequest value) {
                relayLog.record(value.getCouponId());
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
