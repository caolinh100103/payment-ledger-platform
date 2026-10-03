package com.payledger.security;

/**
 * What a signed-in person may do. ADMIN implies OPERATOR and AUDITOR (see {@link SecurityConfig#roleHierarchy()}),
 * but no role implies CUSTOMER: staff manage accounts, they never spend from them.
 */
public enum Role {
    /** Uses their own accounts: views them and transfers money from them. */
    CUSTOMER,
    /** Back office: freezes and unfreezes accounts, reverses transfers, unlocks users. */
    OPERATOR,
    /** Read-only access to the audit trail. */
    AUDITOR,
    /** Manages users and API keys. */
    ADMIN;

    /** The Spring Security authority, e.g. {@code ROLE_OPERATOR}. */
    public String authority() {
        return "ROLE_" + name();
    }
}
