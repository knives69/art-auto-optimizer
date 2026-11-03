package dev.art.optimizer;

public class AppItem {
    public final String packageName;
    public final String label;
    public boolean isSpeed;
    public boolean isNonOptimizable;
    public String currentFilter;
    public boolean isCompiling;

    public AppItem(String packageName, String label, boolean isSpeed, boolean isNonOptimizable, String currentFilter) {
        this.packageName = packageName;
        this.label = label;
        this.isSpeed = isSpeed;
        this.isNonOptimizable = isNonOptimizable;
        this.currentFilter = currentFilter;
        this.isCompiling = false;
    }
}
