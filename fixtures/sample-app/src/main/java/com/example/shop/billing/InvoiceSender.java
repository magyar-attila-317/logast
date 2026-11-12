package com.example.shop.billing;

import org.springframework.stereotype.Component;

import java.util.List;

/** Writes without logging (STATE_CHANGE_UNLOGGED) and loops without a summary (BATCH_SUMMARY_MISSING). */
@Component
public class InvoiceSender {
    private final InvoiceRepository invoices;

    public InvoiceSender(InvoiceRepository invoices) {
        this.invoices = invoices;
    }

    public void sendAll(List<Invoice> open) {
        for (Invoice invoice : open) { // expect: BATCH_SUMMARY_MISSING
            send(invoice);
        }
    }

    public void send(Invoice invoice) {
        invoices.save(invoice.markSent()); // expect: STATE_CHANGE_UNLOGGED
    }

    public Invoice cancel(String invoiceId) {
        return invoices.save(new Invoice(invoiceId, null, false)); // expect: STATE_CHANGE_UNLOGGED
    }
}
