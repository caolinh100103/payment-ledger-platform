package com.payledger.security;

import java.util.Set;

/**
 * Who is behind the current request: a person signed in with an access token, or a machine client with an API key.
 * Controllers receive it as a method argument (see {@link CurrentActor}).
 *
 * @param id          the user id (the JWT subject) for a user, the key id for an API key; a fixed name otherwise
 * @param authorities everything granted, including roles implied by the hierarchy
 */
public record Actor(Kind kind, String id, Set<String> authorities) {

    /** A request without credentials, e.g. a sign-up. */
    public static final Actor ANONYMOUS = new Actor(Kind.ANONYMOUS, "anonymous", Set.of());
    /** Work started by the platform itself, e.g. a scheduled job or the startup bootstrap. */
    public static final Actor SYSTEM = new Actor(Kind.SYSTEM, "system", Set.of());

    public enum Kind {
        USER, API_KEY, ANONYMOUS, SYSTEM
    }

    public Actor {
        authorities = Set.copyOf(authorities);
    }

    /** The stable name recorded in audit trails, e.g. {@code user:3f2a…}. */
    public String name() {
        return switch (kind) {
            case USER -> "user:" + id;
            case API_KEY -> "apikey:" + id;
            case ANONYMOUS, SYSTEM -> id;
        };
    }

    public boolean hasRole(Role role) {
        return authorities.contains(role.authority());
    }

    public boolean isUser() {
        return kind == Kind.USER;
    }
}
