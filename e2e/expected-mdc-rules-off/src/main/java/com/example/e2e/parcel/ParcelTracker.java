package com.example.e2e.parcel;

import com.example.e2e.logging.MdcScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class ParcelTracker {
    private static final Logger log = LoggerFactory.getLogger(ParcelTracker.class);

    void track(String parcelCode, String traceId) {
        try (var mdc = MdcScope.of("parcel_code", parcelCode)) {
            MDC.put("traceId", traceId);
            log.info("Parcel tracking started");
            MDC.remove("traceId");
        }
    }

    void weigh(String parcelCode) {
        try (var mdc = MdcScope.of("parcel_code", parcelCode)) {
            log.info("Parcel weighing started");
        }
    }

    void sort(String parcelCode, String traceId) {
        try {
            MDC.put("traceId", traceId);
            MDC.put("parcel_code", parcelCode);
            log.info("Parcel sorting started");
        } finally {
            MDC.clear();
        }
    }
}
