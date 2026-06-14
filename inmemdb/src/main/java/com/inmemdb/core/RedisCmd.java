package com.inmemdb.core;

/**
 * Represents a parsed Redis command with the command name and its arguments.
 */
public class RedisCmd {
    private final String cmd;
    private final String[] args;

    public RedisCmd(String cmd, String[] args) {
        this.cmd = cmd;
        this.args = args;
    }

    public String getCmd() {
        return cmd;
    }

    public String[] getArgs() {
        return args;
    }
}
