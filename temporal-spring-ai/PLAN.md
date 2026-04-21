# Plan: MCP bean lookup by type, not hard-coded name

## Scope

`McpPlugin.getMcpClients()` currently does:

```java
if (applicationContext.containsBean("mcpSyncClients")) {
    Object bean = applicationContext.getBean("mcpSyncClients");
    if (bean instanceof List<?> clientList && !clientList.isEmpty()) {
        mcpClients = (List<McpSyncClient>) clientList;
    }
}
```

This is brittle:
- Hard-coded bean name — any Spring AI rename (e.g. `mcpAsyncClients` vs
  `mcpSyncClients`, or a future `mcpClientRegistry`) breaks the integration
  silently.
- Users who define additional `McpSyncClient` beans outside the Spring AI
  auto-configuration aren't picked up.
- The unchecked cast is a runtime landmine if anyone ever puts a
  differently-typed `List` bean at that name.

## Fix

Replace with type-based lookup:

```java
Map<String, McpSyncClient> beans =
    applicationContext.getBeansOfType(McpSyncClient.class);
```

Collect `beans.values()` into a `List<McpSyncClient>`. This picks up:
- Whatever Spring AI's MCP auto-configuration registers today.
- Any user-defined `McpSyncClient @Bean` declarations.
- Any future rename by Spring AI, as long as the type stays the same.

## Files to change

- `src/main/java/io/temporal/springai/plugin/McpPlugin.java`
  - Replace the `containsBean("mcpSyncClients")` block in `getMcpClients()`
    with `applicationContext.getBeansOfType(McpSyncClient.class)`.
  - Remove the `@SuppressWarnings("unchecked")` and the unchecked cast.
  - Log the bean names discovered, not just the count, to aid debugging.
  - Keep `McpClientActivityImpl`'s duplicate-name check — if two clients
    advertise the same `clientInfo().name()`, that still needs to error.

## Test plan

- Unit test with a mocked `ApplicationContext.getBeansOfType` returning two
  distinct `McpSyncClient` beans — verify both reach
  `McpClientActivityImpl`.
- Unit test where the context has zero `McpSyncClient` beans — verify the
  plugin stays in the "no clients yet" state and
  `SmartInitializingSingleton.afterSingletonsInstantiated` handles it
  cleanly.

## PR

**Title:** `temporal-spring-ai: discover MCP clients by type, not by bean name`

**Body:**

```
## What was changed
`McpPlugin.getMcpClients()` now calls
`ApplicationContext.getBeansOfType(McpSyncClient.class)` instead of
looking up the hard-coded bean name `"mcpSyncClients"`. The unchecked
cast is removed; discovered bean names are logged for easier debugging.

## Why?
The old bean-name lookup broke silently whenever Spring AI changed the
name its MCP auto-configuration used, and it ignored user-defined
`McpSyncClient` beans entirely. Type-based discovery is more robust,
works with arbitrary user configuration, and removes an unchecked cast.
```
