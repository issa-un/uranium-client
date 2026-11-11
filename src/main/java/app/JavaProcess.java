package app;

/** A running Java program: its PID, its program file, and whether it looks like Minecraft. */
record JavaProcess(String pid, String executable, boolean minecraft) {
    @Override
    public String toString() {
        return (minecraft ? "Minecraft  " : "") + executable + " (PID " + pid + ")";
    }
}
