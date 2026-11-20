package com.codeswitch.llm;

/** Port to the language model. Agent code never sees vendor SDK types. */
public interface LlmClient {

    ToolSession startToolSession(ToolSessionRequest request);

    boolean isConfigured();
}
