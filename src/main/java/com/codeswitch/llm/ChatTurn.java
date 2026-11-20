package com.codeswitch.llm;

public record ChatTurn(Role role, String text) {

    public enum Role { USER, ASSISTANT }
}
