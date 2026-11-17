package com.example.e2e.box;

public final class BoxAuditEntry {
    private final String action;
    private final String boxId;
    private final String by;

    public BoxAuditEntry(String action, String boxId, String by) {
        this.action = action;
        this.boxId = boxId;
        this.by = by;
    }

    public static BoxAuditEntryBuilder builder() {
        return new BoxAuditEntryBuilder();
    }

    @Override
    public String toString() {
        return by + " " + action + " " + boxId;
    }

    /** Named as Lombok names it: its steps are no audit records. */
    public static final class BoxAuditEntryBuilder {
        private String action;
        private String boxId;
        private String by;

        public BoxAuditEntryBuilder action(String action) {
            this.action = action;
            return this;
        }

        public BoxAuditEntryBuilder box(String boxId) {
            this.boxId = boxId;
            return this;
        }

        public BoxAuditEntryBuilder by(String by) {
            this.by = by;
            return this;
        }

        public BoxAuditEntry build() {
            return new BoxAuditEntry(action, boxId, by);
        }
    }
}
