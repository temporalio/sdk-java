# Temporal Workflow Check for Java

Temporal workflowcheck is a utility scans Java bytecode looking for workflow implementation methods that do invalid
things. This mostly centers around
[workflow logic constraints](https://docs.temporal.io/dev-guide/java/foundations#workflow-logic-requirements) that
require workflows are deterministic. Currently it will catch when a workflow method does any of the following:

* Invokes a method that is configured as invalid (e.g. threading, IO, random, system time, etc)
* Accesses a static field configured as invalid (e.g. `System.out`)
* Accesses a non-final static field
* Invokes a method that itself violates any of the above rules

With the last rule, that means this analyzer is recursive and gathers information transitively to ensure
non-deterministic calls aren't made indirectly.

⚠️ BETA

This software is beta quality. We are gathering feedback before considering it stable.

## Running

### Prerequisites

* JDK 8+

### Running manually

The all-in-one JAR is best for running manually. Either download the latest version `-all.jar` from
https://repo1.maven.org/maven2/io/temporal/temporal-workflowcheck or build via `gradlew :temporal-workflowcheck:build`
then obtain `-all.jar` in `temporal-workflowcheck/build/libs`.

Simply running the following will show help text:

    java -jar path/to/temporal-workflowcheck-<version>-all.jar --help

Replace `<version>` with the actual version. The `check` call runs the workflow check and it accepts classpath entries
as arguments, for example:

    java -jar path/to/temporal-workflowcheck-<version>-all.jar check path/to/my.jar path/to/my/classes/

The `check` command accepts the following arguments:

* `--config <config>` - Path to a `.properties` configuration file. Multiple `--config` arguments can be provided with
  the later overriding the earlier. See the [Configuration](#configuration) section for details.
* `--no-default-config` - If present, the default configuration file will not be the implied first configuration file.
* `--show-valid` - In addition to showing invalid workflow methods, also show which workflow methods are valid.
* `<classpath...>` - All other arguments are classpath entries. This accepts the same values as `-cp` on `java`
  commands. Each entry can be a set of entries separated by platform-specific path separator (i.e. `;` for Windows or
  `:` for Nix), or prefixed with an `@` symbol saying it's a file with entries one per line, or just as separate
  arguments. They are all combined to one large classpath when running.

### Running in a Gradle project

See the [Gradle sample](samples/gradle).

### Running in a Maven project

See the [Maven sample](samples/maven).

### Running programmatically

The workflowcheck utility is also a library. The `io.temporal.workflowcheck.WorkflowCheck` class can be instantiated
with a `io.temporal.workflowcheck.Config` and then `findWorkflowClasses` can be run with classpath entries. This will
return details about every workflow method implementation found, including invalid pieces.

## Usage

To use workflowcheck effectively, users may have to add configuration and warning-suppression to properly handle false
positives.

### Configuration

workflowcheck configuration is done via `.properties` file(s). The main use of configuration is to configure what the
system considers an "invalid" method or field. Each property is in the format:

```
temporal.workflowcheck.invalid.[[some/package/]ClassName.]memberName[(Lmethod/Descriptor;)V]=true|false
```

The key names after `temporal.workflowcheck.invalid.` are known as "descriptor patterns" and these patterns are checked
to see whether a call or field access is invalid. If the value is `true` the pattern is considered invalid and if it is
`false` it is considered valid. When supplying properties files as configuration, the later-provided configuration keys
overwrite the earlier keys. During checking, the more-specific patterns are checked first and the first, most-specific
one to say whether it is valid or invalid is what is used. This means that, given the following two properties:

```
temporal.workflowcheck.invalid.my/package/MyUnsafeClass=true
temporal.workflowcheck.invalid.my/package/MyUnsafeClass.safeMethod=false
```

Every method and static field on `my.package.MyUnsafeClass` is considered invalid _except_ for `safeMethod`.

The [implied default configuration](src/main/resources/io/temporal/workflowcheck/workflowcheck.properties) contains a
good set of default invalid/valid configurations to catch most logic mistakes. Additional configurations can be more
specific. For example, the default configuration disallows any calls on `java.lang.Thread`. But if, say, a failure is
reported for `java.lang.Thread.getId()` but it is known to be used safely/deterministically by Temporal's definition,
then a configuration file with the following will make it valid:

```
temporal.workflowcheck.invalid.java/lang/Thread.getId=false
```

When the system checks for valid/invalid, it checks the most-specific to least-specific (kinda), trying to find whether
there is a key present (regardless of whether it is `true` or `false`) and it uses that value. For example, when the
system encounters a call to `myString.indexOf("foo", 123)`, it will check for the following keys in order (the
`temporal.workflowcheck.invalid.` prefix is removed for brevity):

* `java/lang/String.indexOf(Ljava/lang/String;I)`
* `java/lang/String.indexOf`
* `String.indexOf(Ljava/lang/String;I)`
* `String.indexOf`
* `indexOf(Ljava/lang/String;I)`
* `indexOf`
* `String`
* `java/lang/String`
* `java/lang`
* `java`

The class name is the binary class name as defined by the JVM spec. The method descriptor is the method descriptor as
defined by the JVM spec but with the return type removed (return types can be covariant across interfaces and therefore
not useful for our strict checking).

Note, in order to support superclass/superinterface checking, if nothing is found for the type, the same method is
checked against the superclass and superinterfaces. So technically `java/lang/Object.indexOf` would match even though
that method does not exist. This is by intention to allow marking entire hierarchies of methods invalid (e.g.
`Map.forEach=true` but `LinkedHashMap.forEach=false`).

There is advanced logic with inheritance and how the proper implementation of a method is determined including resolving
interface default methods, but that is beyond this documentation. Users are encouraged to write tests confirming
behavior of configuration keys.

### Suppressing warnings

Usually in Java when wanting to suppress warnings on source code, the `@SuppressWarnings` annotation in `java.lang` is
used. However, workflowcheck operates on bytecode and that annotation is not preserved in bytecode. As an alternative,
the `@WorkflowCheck.SuppressWarnings` annotation is available in `io.temporal.workflowcheck` that will ignore errors.
For instance, one could have:

```java
@WorkflowCheck.SuppressWarnings
public long getCurrentMillis() {
  return System.currentTimeMillis();
}
```

This will now consider `getCurrentMillis` as valid regardless of what's inside it. Since the retention policy on the
`@WorkflowCheck.SuppressWarnings` annotation is `CLASS`, it is not even required to be present at runtime. So the
`workflowcheck` library can just be a compile-only dependency (i.e. `provided` scope in Maven or `compileOnly` in
Gradle), the library is not needed at runtime.

the `@WorkflowCheck.SuppressWarnings` annotation provides an `invalidMembers` field that can be a set of the descriptor
patterns mentioned in the [Configuration](#configuration) section above. When not set, every invalid piece is accepted,
so users are encouraged to at least put the method/field name they want to allow so accidental suppression is avoided.
That means the above snippet would become:

```java
@WorkflowCheck.SuppressWarnings(invalidMembers = "currentTimeMillis")
public long getCurrentMillis() {
  return System.currentTimeMillis();
}
```

_Technically_ there is an inline suppression approach that is a runtime no-op that is `WorkflowCheck.suppressWarnings()`
invocation followed by `WorkflowCheck.restoreWarnings()` later. So the above _could_ be:

```java
public long getCurrentMillis() {
  WorkflowCheck.suppressWarnings("currentTimeMillis");
  var l = System.currentTimeMillis();
  WorkflowCheck.restoreWarnings();
  return l;
}
```

However this is hard to use for a couple of reasons. First, the methods are evaluated when they are seen in bytecode,
not in the order they appear in logic. `javac` bytecode ordering is not the same as source ordering. Second, this does
require a runtime dependency on the workflowcheck library. Users are discouraged from ever using this and should use the
annotation instead.

### Best practices

#### False positives

When encountering a false positive in a commonly used or third-party library, decide how far up the call stack the call
is considered deterministic by Temporal's definition. Then configure the method as "valid".

When encountering a specific false positive in workflow code, consider moving it to its own method and adding
`@WorkflowCheck.SuppressWarnings` for just that method (or just add that annotation on the method but target the
specific call). Annotations can be better than using configuration files for small amounts of local workflow code
because the configuration file can get really cluttered with single-workflow-specific code and using configuration makes
it hard for code readers to see that it is intentionally marked as valid.

#### Collection iteration

By default, iterating any `Iterable` is considered unsafe with specific exceptions carved out for `LinkedHashMap`,
`List`, `SortedMap`, and `SortedSet`. But in many cases, static analysis code cannot detect that something is safe. For
example:

```
var map = new TreeMap<>(Map.of("a", "b"));
for (var entry : map.entrySet()) {
  // ...
}
```

The implicit `Set.iterator` call on the `entrySet` will be considered invalid, because `entrySet`'s type is `Set`. The
same thing happens when a higher level collection type is used, for example:

```
Collection<String> strings = new TreeSet<>(List.of("foo", "bar"));
for (var string : strings) {
  // ...
}
```

In cases where the higher-level type can be used, try to use that. So in the above sample change to
`SortedSet<String> strings`. If that is not available, wrapping as a list just for iteration is acceptable. Workflow
performance is not the same as general Java code performance, so it is often totally reasonable to accept the hit on
iteration. So for the first example, it could be written like so:

```
var map = new TreeMap<>(Map.of("a", "b"));
for (var entry : new ArrayList<>(map.entrySet())) {
  // ...
}
```

In advanced situations, warning-suppression approaches can be applied.

## Internals

The following sections give some insight into the development of workflowcheck.

### How it works

Workflowcheck works by scanning all non-standard-library classes on the classpath. When scanning, in addition to some
other details, the following bits of information are collected for every method:

* Whether the method is a workflow declaration (e.g. interface methods with `@WorkflowMethod`)
* Unsuppressed/unconfigured method invocations
* Field accesses configured as invalid
* Unsuppressed/unconfigured static field access

This intentionally, to avoid eager recursion issues, does not traverse the call graph eagerly.

Then for every method of every scanned class, it is checked whether it is a workflow method. This is done by checking if
it contains a body and overrides any super interface workflow declaration at any level. For every method that is a
workflow implementation, it is processed for invalidity.

The invalidity processor is a recursive call that checks a method for whether it is invalid. Specifically, it:

* Considers all invalid field accesses as invalid member accesses
* Resolves target of all static field accesses and if the fields are non-final static fields, considers them invalid
  member accesses
* Checks all method calls to see if they are invalid by:
  * Finding the most-specific configured descriptor pattern, using advanced most-specific logic when encountering
    ambiguous interface depth. If it is configured invalid, mark as such. Regardless of whether invalid or valid, if it
    was configured at all, do not go to the next step.
  * Resolve the most specific implementation of a method. Just because `Foo.bar()` is the method invocation doesn't mean
    `Foo` declares `bar()`, it may inherited. Advanced virtual resolution logic is used to find the first implementation
    in the hierarchy that it refers to. If/when resolved, that method is recursively checked for invalidity via this
    same processor (storing itself to prevent recursion) and if it's invalid, then so is this call.

This algorithm ensures that configuration can apply at multiple levels of hierarchy but transitive code-based method
invalidity is only on the proper implementation. So if `Foo.bar()` is bad but `ExtendsFoo.bar()` is ok, the former does
not report a false positive (unless of course `ExtendsFoo.bar()` invokes `super.bar()` which would transitively mark it
as invalid).

During this resolution, the call graph is constructed with access to the class/method details for each transitive
non-recursive invocation. Once complete, all the valid methods are trimmed to relieve memory pressure and all classes
with workflow implementations properly contain their direct and indirect invalid member accesses.

The printer then prints these out.

### FAQ

**Why not use static analysis library X?**

One of the primary features of workflowcheck is to find whether a method is invalid transitively (i.e. building a call
graph) across existing bytecode including the Java standard library. During research, no tool was found to be able to do
this without significant effort or performance penalties. Approaches researched:

* Checkstyle, ErrorProne, PMD, etc - not built for transitive bytecode checking
* Custom annotation processor - Bad caching across compilation units, JDK compiler API hard to use (have to add-opens
  for modules for sun compiler API, or have to use third party)
* Soot/SootUp - Soot is too old, SootUp is undergoing new development but was still a bit rough when tried (e.g. failed
  when an annotation wasn't on the classpath)
* ClassGraph - Does not say which methods call other methods (so not a call graph)
* SemGrep - Does not seem to support recursive call-graph analysis on bytecode to find bad calls at arbitrary call
  depths
* CodeQL - Too slow
* Doop, jQAssistant, java-callgraph, etc - not up to date

Overall, walking the classpath using traditional, high-performance bytecode visiting via OW2 ASM is a good choice for
this project's needs.

**Why use `.properties` files instead of a better configuration format?**

A goal of the workflowcheck project is to have as few dependencies as possible.

**Why not use more modern Java features in the code?**

The code is optimized for performance, so direct field access instead of encapsulation, looping instead of streaming,
mutable objects instead of records, etc may be present. But the user-facing API does follow proper practices.

### TODO

Currently, this project is missing many features:

* Accept environment variables to point to config files
* Accept environment variables to provide specific config properties
* Accept Java system properties to point to config files
* Accept Java system properties to provide specific config properties
* Check lambda contents but avoid SideEffect
* Module support
* Prevent field mutation in queries and update validators
* Config prebuilding where you can give a set of packages and it will generate a `.properties` set of invalid methods
  and save from having to reread the class files of that package at runtime
  * Also consider shipping with prebuilt config for Java standard library through Java 21
* Support SARIF output for better integration with tooling like GitHub actions
* Change output to work with IntelliJ's console linking better (see
  [this SO answer](https://stackoverflow.com/questions/7930844/is-it-possible-to-have-clickable-class-names-in-console-output-in-intellij))
* Support an HTML-formatted result with collapsible hierarchy
* For very deep trees, support `[...]` by default to replace all but the two beginning and two end entries (with CLI
  option to show more)