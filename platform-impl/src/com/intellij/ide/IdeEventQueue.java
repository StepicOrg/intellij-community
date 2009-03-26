package com.intellij.ide;


import com.intellij.Patches;
import com.intellij.concurrency.JobSchedulerImpl;
import com.intellij.ide.dnd.DnDManager;
import com.intellij.ide.dnd.DnDManagerImpl;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.impl.ApplicationImpl;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.keymap.impl.IdeKeyEventDispatcher;
import com.intellij.openapi.keymap.impl.IdeMouseEventDispatcher;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.wm.ex.WindowManagerEx;
import com.intellij.util.Alarm;
import com.intellij.util.ProfilingUtil;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.HashMap;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;


/**
 * @author Vladimir Kondratyev
 * @author Anton Katilin
 */

public class IdeEventQueue extends EventQueue {

  private static final Logger LOG = Logger.getInstance("#com.intellij.ide.IdeEventQueue");

  private static final boolean DEBUG = LOG.isDebugEnabled();

  /**
   * Adding/Removing of "idle" listeners should be thread safe.
   */
  private final Object myLock = new Object();

  private final ArrayList<Runnable> myIdleListeners = new ArrayList<Runnable>(2);

  private final ArrayList<Runnable> myActivityListeners = new ArrayList<Runnable>(2);

  private final Alarm myIdleRequestsAlarm = new Alarm();

  private final Alarm myIdleTimeCounterAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD);

  private long myIdleTime;

  private final Map<Runnable, MyFireIdleRequest> myListener2Request = new HashMap<Runnable, MyFireIdleRequest>(); // IdleListener -> MyFireIdleRequest

  private final IdeKeyEventDispatcher myKeyEventDispatcher = new IdeKeyEventDispatcher();

  private final IdeMouseEventDispatcher myMouseEventDispatcher = new IdeMouseEventDispatcher();

  private final IdePopupManager myPopupManager = new IdePopupManager();


  private boolean mySuspendMode;

  /**
   * We exit from suspend mode when focus owner changes and no more WindowEvent.WINDOW_OPENED events
   * <p/>
   * in the queue. If WINDOW_OPENED event does exists in the queus then we restart the alarm.
   */

  private Component myFocusOwner;

  private final Runnable myExitSuspendModeRunnable = new ExitSuspendModeRunnable();

  /**
   * We exit from suspend mode when this alarm is triggered and no mode WindowEvent.WINDOW_OPENED
   * <p/>
   * events in the queue. If WINDOW_OPENED event does exist then we restart the alarm.
   */
  private final Alarm mySuspendModeAlarm = new Alarm();

  /**
   * Counter of processed events. It is used to assert that data context lives only inside single
   * <p/>
   * Swing event.
   */

  private int myEventCount;


  private boolean myIsInInputEvent = false;

  private AWTEvent myCurrentEvent = null;

  private long myLastActiveTime;

  private WindowManagerEx myWindowManager;


  private final Set<EventDispatcher> myDispatchers = new LinkedHashSet<EventDispatcher>();

  private static class IdeEventQueueHolder {
    private static final IdeEventQueue INSTANCE = new IdeEventQueue();
  }
  public static IdeEventQueue getInstance() {
    return IdeEventQueueHolder.INSTANCE;
  }

  private IdeEventQueue() {
    addIdleTimeCounterRequest();
    final KeyboardFocusManager keyboardFocusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager();

    //noinspection HardCodedStringLiteral
    keyboardFocusManager.addPropertyChangeListener("permanentFocusOwner", new PropertyChangeListener() {

      public void propertyChange(final PropertyChangeEvent e) {
        final Application application = ApplicationManager.getApplication();
        if (application == null) {

          // We can get focus event before application is initialized
          return;
        }
        application.assertIsDispatchThread();
        final Window focusedWindow = keyboardFocusManager.getFocusedWindow();
        final Component focusOwner = keyboardFocusManager.getFocusOwner();
        if (mySuspendMode && focusedWindow != null && focusOwner != null && focusOwner != myFocusOwner && !(focusOwner instanceof Window)) {
          exitSuspendMode();
        }
      }
    });
  }


  public void setWindowManager(final WindowManagerEx windowManager) {
    myWindowManager = windowManager;
  }


  private void addIdleTimeCounterRequest() {
    if (ApplicationManager.getApplication().isUnitTestMode()) return;

    myIdleTimeCounterAlarm.cancelAllRequests();
    myLastActiveTime = System.currentTimeMillis();
    myIdleTimeCounterAlarm.addRequest(new Runnable() {
      public void run() {
        myIdleTime += System.currentTimeMillis() - myLastActiveTime;
        addIdleTimeCounterRequest();
      }
    }, 20000, ModalityState.NON_MODAL);
  }


  public boolean shouldNotTypeInEditor() {
    return myKeyEventDispatcher.isWaitingForSecondKeyStroke() || mySuspendMode;
  }


  private void enterSuspendMode() {
    mySuspendMode = true;
    myFocusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
    mySuspendModeAlarm.cancelAllRequests();
    mySuspendModeAlarm.addRequest(myExitSuspendModeRunnable, 750);
  }


  /**
   * Exits supend mode and pumps all suspended events.
   */

  private void exitSuspendMode() {
    if (peekEvent(WindowEvent.WINDOW_OPENED) != null) {

      // We have to exit from suspend mode (focus owner changes or alarm is triggered) but

      // WINDOW_OPENED isn't dispatched yet. In this case we have to restart the alarm until

      // all WINDOW_OPENED event will be processed.
      mySuspendModeAlarm.cancelAllRequests();
      mySuspendModeAlarm.addRequest(myExitSuspendModeRunnable, 250);
    }
    else {

      // Now we can pump all suspended events.
      mySuspendMode = false;
      myFocusOwner = null; // to prevent memory leaks
    }
  }


  public void addIdleListener(@NotNull final Runnable runnable, final int timeout) {
    LOG.assertTrue(timeout > 0);
    synchronized (myLock) {
      myIdleListeners.add(runnable);
      final MyFireIdleRequest request = new MyFireIdleRequest(runnable, timeout);
      myListener2Request.put(runnable, request);
      myIdleRequestsAlarm.addRequest(request, timeout);
    }
  }


  public void removeIdleListener(@NotNull final Runnable runnable) {
    synchronized (myLock) {
      final boolean wasRemoved = myIdleListeners.remove(runnable);
      if (!wasRemoved) {
        LOG.assertTrue(false, "unknown runnable: " + runnable);
      }
      final MyFireIdleRequest request = myListener2Request.remove(runnable);
      LOG.assertTrue(request != null);
      myIdleRequestsAlarm.cancelRequest(request);
    }
  }


  public void addActivityListener(@NotNull final Runnable runnable) {
    synchronized (myLock) {
      myActivityListeners.add(runnable);
    }
  }
  public void addActivityListener(@NotNull final Runnable runnable, Disposable parentDisposable) {
    synchronized (myLock) {
      ContainerUtil.add(runnable, myActivityListeners, parentDisposable);
    }
  }


  public void removeActivityListener(@NotNull final Runnable runnable) {
    synchronized (myLock) {
      final boolean wasRemoved = myActivityListeners.remove(runnable);
      if (!wasRemoved) {
        LOG.assertTrue(false, "unknown runnable: " + runnable);
      }
    }
  }


  public void addDispatcher(final EventDispatcher dispatcher, Disposable parent) {
    myDispatchers.add(dispatcher);
    if (parent != null) {
      Disposer.register(parent, new Disposable() {
        public void dispose() {
          removeDispatcher(dispatcher);
        }
      });
    }
  }

  public void removeDispatcher(EventDispatcher dispatcher) {
    myDispatchers.remove(dispatcher);
  }


  public int getEventCount() {
    return myEventCount;
  }


  public void setEventCount(int evCount) {
    myEventCount = evCount;
  }


  public AWTEvent getTrueCurrentEvent() {
    return myCurrentEvent;
  }

  //[jeka] commented for performance reasons

  /*

  public void postEvent(final AWTEvent e) {

    // [vova] sometime people call SwingUtilities.invokeLater(null). To

    // find such situations we will specially check InvokationEvents

    try {

      if (e instanceof InvocationEvent) {

        //noinspection HardCodedStringLiteral

        final Field field = InvocationEvent.class.getDeclaredField("runnable");

        field.setAccessible(true);

        final Object runnable = field.get(e);

        if (runnable == null) {

          //noinspection HardCodedStringLiteral

          throw new IllegalStateException("InvocationEvent contains null runnable: " + e);

        }

      }

    }

    catch (final Exception exc) {

      throw new Error(exc);

    }

    super.postEvent(e);

  }

  */

  public void dispatchEvent(final AWTEvent e) {
    long t = 0;

    if (DEBUG) {
      t = System.currentTimeMillis();
      ProfilingUtil.startCPUProfiling();
    }

    boolean wasInputEvent = myIsInInputEvent;
    myIsInInputEvent = e instanceof InputEvent || e instanceof InputMethodEvent || e instanceof WindowEvent || e instanceof ActionEvent;
    AWTEvent oldEvent = myCurrentEvent;
    myCurrentEvent = e;

    JobSchedulerImpl.suspend();
    try {
      _dispatchEvent(e);
    }
    finally {
      myIsInInputEvent = wasInputEvent;
      myCurrentEvent = oldEvent;
      JobSchedulerImpl.resume();

      if (DEBUG) {
        final long processTime = System.currentTimeMillis() - t;
        if (processTime > 100) {
          final String path = ProfilingUtil.captureCPUSnapshot();

          LOG.debug("Long event: " + processTime + "ms - " + toDebugString(e));
          LOG.debug("Snapshot taken: " + path);
        }
        else {
          ProfilingUtil.stopCPUProfiling();
        }
      }
    }
  }

  @SuppressWarnings({"ALL"})
  private static String toDebugString(final AWTEvent e) {
    if (e instanceof InvocationEvent) {
      try {
        final Field f = InvocationEvent.class.getDeclaredField("runnable");
        f.setAccessible(true);
        Object runnable = f.get(e);

        return "Invoke Later[" + runnable.toString() + "]";
      }
      catch (NoSuchFieldException e1) {
      }
      catch (IllegalAccessException e1) {
      }
    }
    return e.toString();
  }


  private void _dispatchEvent(final AWTEvent e) {
    if (e.getID() == MouseEvent.MOUSE_DRAGGED) {
      DnDManagerImpl dndManager = (DnDManagerImpl)DnDManager.getInstance();
      if (dndManager != null) {
        dndManager.setLastDropHandler(null);
      }
    }


    myEventCount++;


    if (processAppActivationEvents(e)) return;

    if (!myPopupManager.isPopupActive()) {

      // Enter to suspend mode if necessary. Suspend will cancel processing of actions mapped to the keyboard shortcuts.
      if (e instanceof KeyEvent) {
        if (!mySuspendMode && peekEvent(WindowEvent.WINDOW_OPENED) != null) {
          enterSuspendMode();
        }
      }
    }

    if (e instanceof WindowEvent) {
      ActivityTracker.getInstance().inc();
    }


    // Process "idle" and "activity" listeners
    if (e instanceof KeyEvent || e instanceof MouseEvent) {
      ActivityTracker.getInstance().inc();

      synchronized (myLock) {
        myIdleRequestsAlarm.cancelAllRequests();
        for (Runnable idleListener : myIdleListeners) {
          final MyFireIdleRequest request = myListener2Request.get(idleListener);
          if (request == null) {
            LOG.error("There is no request for " + idleListener);
          }
          int timeout = request.getTimeout();
          myIdleRequestsAlarm.addRequest(request, timeout, ModalityState.NON_MODAL);
        }
        if (KeyEvent.KEY_PRESSED == e.getID() || KeyEvent.KEY_TYPED == e.getID() || MouseEvent.MOUSE_PRESSED == e.getID() ||
            MouseEvent.MOUSE_RELEASED == e.getID() || MouseEvent.MOUSE_CLICKED == e.getID()) {
          addIdleTimeCounterRequest();
          for (Runnable activityListener : myActivityListeners) {
            activityListener.run();
          }
        }
      }
    }
    if (myPopupManager.isPopupActive() && myPopupManager.dispatch(e)) {
      return;
    }

    for (EventDispatcher eachDispatcher : myDispatchers) {
      if (eachDispatcher.dispatch(e)) {
        return;
      }
    }

    if (e instanceof InputMethodEvent) {
      if (SystemInfo.isMac && myKeyEventDispatcher.isWaitingForSecondKeyStroke()) {
        return;
      }
    }
    if (e instanceof InputEvent && Patches.SPECIAL_WINPUT_METHOD_PROCESSING) {
      final InputEvent inputEvent = (InputEvent)e;
      if (!inputEvent.getComponent().isShowing()) {
        return;
      }
    }
    if (e instanceof ComponentEvent && myWindowManager != null) {
      myWindowManager.dispatchComponentEvent((ComponentEvent)e);
    }
    if (e instanceof KeyEvent) {
      if (mySuspendMode || !myKeyEventDispatcher.dispatchKeyEvent((KeyEvent)e)) {
        defaultDispatchEvent(e);
      }
      else {
        ((KeyEvent)e).consume();
        defaultDispatchEvent(e);
      }
    }
    else if (e instanceof MouseEvent) {
      if (!myMouseEventDispatcher.dispatchMouseEvent((MouseEvent)e)) {
        defaultDispatchEvent(e);
      }
    }
    else {
      defaultDispatchEvent(e);
    }
  }

  private boolean processAppActivationEvents(AWTEvent e) {
    final Application app = ApplicationManager.getApplication();
    if (!(app instanceof ApplicationImpl)) return false;

    ApplicationImpl appImpl = (ApplicationImpl)app;

    if (e instanceof WindowEvent) {
      WindowEvent we = (WindowEvent)e;
      if (we.getID() == WindowEvent.WINDOW_GAINED_FOCUS && we.getWindow() != null) {
        if (we.getOppositeWindow() == null && !appImpl.isActive()) {
          appImpl.tryToApplyActivationState(true, we.getWindow());
          return true;
        }
      } else if (we.getID() == WindowEvent.WINDOW_LOST_FOCUS && we.getWindow() != null) {
        if (we.getOppositeWindow() == null && appImpl.isActive()) {
          appImpl.tryToApplyActivationState(false, we.getWindow());
          return true;
        }
      }
    }


    return false;
  }


  private void defaultDispatchEvent(final AWTEvent e) {
    try {
      super.dispatchEvent(e);
    }
    catch (ProcessCanceledException pce) {
      throw pce;      
    } catch (Throwable exc) {
      LOG.error("Error during dispatching of " + e, exc);
    }
  }


  public void flushQueue() {
    while (true) {
      AWTEvent event = peekEvent();
      if (event == null) return;
      try {
        AWTEvent event1 = getNextEvent();
        dispatchEvent(event1);
      }
      catch (Exception e) {
        LOG.error(e); //?
      }
    }
  }

  public void pumpEventsForHierarchy(Component modalComponent, Condition<AWTEvent> exitCondition) {
    AWTEvent event;
    do {
      try {
        event = getNextEvent();
        boolean eventOk = true;
        if (event instanceof InputEvent) {
          final Object s = event.getSource();
          if (s instanceof Component) {
            Component c = (Component)s;
            Window modalWindow = SwingUtilities.windowForComponent(modalComponent);
            while (c != null && c != modalWindow) c = c.getParent();
            if (c == null) {
              eventOk = false;
              ((InputEvent)event).consume();
            }
          }
        }

        if (eventOk) {
          dispatchEvent(event);
        }
      }
      catch (Throwable e) {
        LOG.error(e);
        event = null;
      }
    }
    while (!exitCondition.value(event));
  }


  public interface EventDispatcher {
    boolean dispatch(AWTEvent e);
  }


  private final class MyFireIdleRequest implements Runnable {
    private final Runnable myRunnable;
    private final int myTimeout;


    public MyFireIdleRequest(final Runnable runnable, final int timeout) {
      myTimeout = timeout;
      myRunnable = runnable;
    }


    public void run() {
      myRunnable.run();
      synchronized (myLock) {
        myIdleRequestsAlarm.addRequest(this, myTimeout, ModalityState.NON_MODAL);
      }
    }

    public int getTimeout() {
      return myTimeout;
    }
  }


  private final class ExitSuspendModeRunnable implements Runnable {

    public void run() {
      if (mySuspendMode) {
        exitSuspendMode();
      }
    }
  }


  public long getIdleTime() {
    return myIdleTime;
  }


  public IdePopupManager getPopupManager() {
    return myPopupManager;
  }

  public void blockNextEvents(final MouseEvent e) {
    myMouseEventDispatcher.blockNextEvents(e);
  }

}
