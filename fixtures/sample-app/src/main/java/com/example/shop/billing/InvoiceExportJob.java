package com.example.shop.billing;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/** A nightly job that says nothing about what it did (JOB_OUTCOME_MISSING) or why it did nothing (SKIP_UNLOGGED). */
@Component
public class InvoiceExportJob {
    private final InvoiceRepository invoices;
    private final InvoiceSender sender;

    public InvoiceExportJob(InvoiceRepository invoices, InvoiceSender sender) {
        this.invoices = invoices;
        this.sender = sender;
    }

    @Scheduled(cron = "0 0 2 * * *")
    public void export() { // expect: ENTRYPOINT_SILENT, JOB_OUTCOME_MISSING
        List<Invoice> open = invoices.findBySentFalse();
        if (open.isEmpty()) { // expect: SKIP_UNLOGGED
            return;
        }
        sender.sendAll(open);
        sender.cancel("stale");
    }
}
