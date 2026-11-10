package de.robv.android.xposed;
public abstract class XC_MethodHook {
  public XC_MethodHook() {} public XC_MethodHook(int priority) {}
  protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
  protected void afterHookedMethod(MethodHookParam param) throws Throwable {}
  public class Unhook { public void unhook() {} }
  public static final class MethodHookParam {
    public java.lang.reflect.Member method; public Object thisObject; public Object[] args;
    public Object getResult() { return null; } public void setResult(Object r) {}
    public Throwable getThrowable() { return null; }
  }
}
