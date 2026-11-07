package dev.vexelray.framework.core;

import dev.vexelray.framework.api.Stability;
import sibarum.atchung.Topic;

/**
 * <b>Put a window in front of the person, or take it away</b>: what a part sends to open, hide or close one of the
 * application's windows, or put it full screen and back, by its key. The framework carries it out on the main thread, in the main window's frame, and
 * says what happened with {@link WindowOpened} and {@link WindowClosed}.
 *
 * {@snippet :
 * keys.action("window.settings", "Settings", Shortcut.of(Key.COMMA, Modifier.CONTROL))
 *         .sends(windows, WindowCommand.show("settings"));
 * }
 *
 * <p><b>A message, so a send is declared.</b> A part that opens a window takes a {@code Sender<WindowCommand>}, and the
 * processor sees it; a handle whose {@code show()} could be called from anywhere was a send nothing could see. A
 * command for a window that is not open yet, before {@code ATTACH}, is carried out when the window is claimed.
 *
 * <p>The main window's {@link Command#CLOSE} is quitting, by the route its own close button takes, so a part that
 * hears {@link CloseRequested} is asked first. A key no window has is reported in the log and does nothing.
 *
 * @param window  the window's key: {@link CloseRequested#MAIN} for the main one
 * @param command what to do with it
 */
@Stability(Stability.Level.EXPERIMENTAL)
public record WindowCommand(String window, Command command) {

    public static final Topic<WindowCommand> TOPIC = Topic.edge(WindowCommand.class);

    /** Open {@code window} if it is closed, unhide it, raise it and give it the keyboard. */
    public static WindowCommand show(String window) {
        return new WindowCommand(window, Command.SHOW);
    }

    /** Take {@code window} off the screen, keeping it and everything in it. */
    public static WindowCommand hide(String window) {
        return new WindowCommand(window, Command.HIDE);
    }

    /** Show {@code window} if it is not in front of the person, hide it if it is. */
    public static WindowCommand toggle(String window) {
        return new WindowCommand(window, Command.TOGGLE);
    }

    /** Close {@code window} by the route its close button takes; its tree stays, for a later show. */
    public static WindowCommand close(String window) {
        return new WindowCommand(window, Command.CLOSE);
    }

    /**
     * Put {@code window} full screen: borderless over its whole display, not a change of display mode, so switching
     * away is instant. While it is, the window remembers the placement it will go back to, and that is the one
     * remembered between runs. Said as {@link WindowFullScreen}.
     */
    public static WindowCommand fullScreen(String window) {
        return new WindowCommand(window, Command.FULL_SCREEN);
    }

    /** Bring {@code window} back from full screen to the window it was, maximized if it was. */
    public static WindowCommand windowed(String window) {
        return new WindowCommand(window, Command.WINDOWED);
    }

    /** Full screen if {@code window} is not, the window it was if it is. */
    public static WindowCommand toggleFullScreen(String window) {
        return new WindowCommand(window, Command.TOGGLE_FULL_SCREEN);
    }

    /** Hand this command to {@code sink}'s method for it. */
    public void to(Sink sink) {
        command.to(sink);
    }

    public enum Command {
        SHOW {
            @Override
            void to(Sink sink) {
                sink.show();
            }
        },
        HIDE {
            @Override
            void to(Sink sink) {
                sink.hide();
            }
        },
        TOGGLE {
            @Override
            void to(Sink sink) {
                sink.toggle();
            }
        },
        CLOSE {
            @Override
            void to(Sink sink) {
                sink.close();
            }
        },
        FULL_SCREEN {
            @Override
            void to(Sink sink) {
                sink.fullScreen(true);
            }
        },
        WINDOWED {
            @Override
            void to(Sink sink) {
                sink.fullScreen(false);
            }
        },
        TOGGLE_FULL_SCREEN {
            @Override
            void to(Sink sink) {
                sink.toggleFullScreen();
            }
        };

        abstract void to(Sink sink);
    }

    /** What carries out a command: one method per {@link Command}, so a new one is a compile error in every one. */
    public interface Sink {

        void show();

        void hide();

        void toggle();

        void close();

        /** Full screen, or back to the window it was. */
        void fullScreen(boolean on);

        void toggleFullScreen();
    }
}
