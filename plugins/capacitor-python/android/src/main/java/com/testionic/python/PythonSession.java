package com.testionic.python;

import java.util.UUID;

/** 一个 Python 会话（独立 globals + 独立 stdin 队列）。 */
final class PythonSession {

    enum State {
        IDLE("idle"),
        RUNNING("running"),
        TERMINATED("terminated"),
        ERROR("error");

        final String wire;

        State(String wire) {
            this.wire = wire;
        }
    }

    final String id;
    final String name;
    final long createdAt;

    volatile State state = State.IDLE;
    volatile boolean terminated;
    volatile String pythonVersion;

    PythonSession(String name) {
        this.id = "py-" + UUID.randomUUID().toString().substring(0, 8);
        this.name = name;
        this.createdAt = System.currentTimeMillis();
    }

}
