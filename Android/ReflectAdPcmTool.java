public class ReflectAdPcmTool {
  public static void main(String[] args) throws Exception {
    Class<?> cls = Class.forName("com.lm.sdk.AdPcmTool");
    for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
      System.out.println(m.toString());
    }
  }
}
