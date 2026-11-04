import android.util.Base64;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import com.android.uiautomator.core.UiObject;
import com.android.uiautomator.core.UiSelector;
import com.android.uiautomator.testrunner.UiAutomatorTestCase;
import java.nio.charset.StandardCharsets;

public final class UiSetText extends UiAutomatorTestCase {
    private static final class Input extends UiObject {
        Input() {
            super(new UiSelector().packageName("app.zhendong.reamicro").className("android.widget.EditText").focused(true));
        }
        AccessibilityNodeInfo node() {
            return findAccessibilityNodeInfo(2000);
        }
    }
    private static final class ExactAction extends UiObject {
        ExactAction(String text, int instance) {
            super(new UiSelector().packageName("app.zhendong.reamicro").text(text).instance(instance));
        }
        AccessibilityNodeInfo node() {
            return findAccessibilityNodeInfo(2000);
        }
        AccessibilityNodeInfo clickableNode() {
            AccessibilityNodeInfo current = node();
            while (current != null && !current.isClickable()) {
                current = current.getParent();
            }
            return current;
        }
    }
    private static final class DescriptionAction extends UiObject {
        DescriptionAction(String description) {
            super(new UiSelector().packageName("app.zhendong.reamicro").description(description));
        }
        AccessibilityNodeInfo node() {
            return findAccessibilityNodeInfo(2000);
        }
        AccessibilityNodeInfo clickableNode() {
            AccessibilityNodeInfo current = node();
            while (current != null && !current.isClickable()) {
                current = current.getParent();
            }
            return current;
        }
    }
    private static final class ScrollAction extends UiObject {
        ScrollAction() {
            super(new UiSelector().packageName("app.zhendong.reamicro").scrollable(true));
        }
        AccessibilityNodeInfo node() {
            return findAccessibilityNodeInfo(2000);
        }
    }

    public void testSetText() throws Exception {
        assertEquals("app.zhendong.reamicro", getUiDevice().getCurrentPackageName());
        String text = new String(Base64.decode(getParams().getString("text64"), Base64.DEFAULT),
                StandardCharsets.UTF_8);
        if ("scroll-forward".equals(getParams().getString("action")) ||
                "scroll-back".equals(getParams().getString("action"))) {
            ScrollAction target = new ScrollAction();
            assertTrue("Scrollable container is missing", target.waitForExists(2000));
            int action = "scroll-back".equals(getParams().getString("action"))
                    ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
            assertTrue("Scroll action failed", target.node().performAction(action));
            return;
        }
        if ("click-desc".equals(getParams().getString("action"))) {
            DescriptionAction target = new DescriptionAction(text);
            assertTrue("Exact description is missing", target.waitForExists(2000));
            AccessibilityNodeInfo node = target.clickableNode();
            assertTrue("Exact description has no clickable parent", node != null);
            assertTrue("Exact description is disabled", node.isEnabled());
            assertTrue("Exact description click failed", node.performAction(AccessibilityNodeInfo.ACTION_CLICK));
            return;
        }
        if ("click".equals(getParams().getString("action"))) {
            ExactAction target = new ExactAction(text, 0);
            assertTrue("Exact action is missing", target.waitForExists(2000));
            assertFalse("Exact action is ambiguous", new ExactAction(text, 1).exists());
            AccessibilityNodeInfo node = target.clickableNode();
            assertTrue("Exact action has no clickable parent", node != null);
            assertTrue("Exact action is disabled", node.isEnabled());
            assertTrue("Exact action failed", node.performAction(AccessibilityNodeInfo.ACTION_CLICK));
            return;
        }
        Input input = new Input();
        assertTrue("Reader input is missing", input.waitForExists(2000));
        assertEquals("app.zhendong.reamicro", getUiDevice().getCurrentPackageName());
        Bundle arguments = new Bundle();
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        assertTrue("Unable to set reader text", input.node().performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments));
        Thread.sleep(200);
        assertEquals(text, input.getText());
    }
}
