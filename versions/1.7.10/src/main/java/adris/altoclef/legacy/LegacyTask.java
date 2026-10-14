package adris.altoclef.legacy;

interface LegacyTask {
    /** true only after the task's postcondition has been observed. */
    boolean tick(LegacyBot bot);
    String describe();
    default void stop(LegacyBot bot) {}
}
