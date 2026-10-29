package ${packageName};

import sibarum.atchung.Topic;

/**
 * Something the person did that the {@link View} has to act on, sent from the thread it happened on to the view's own
 * lane.
 *
 * <p>A key chord, a menu item and a tab selection all arrive on the handler lane, and the tab list is the view's: so
 * the handler only says what happened, and the view does it, in order, on its own thread. That is what lets
 * {@link Workspace} keep its tabs without a lock. {@code id} is the tab a gesture is about, or the close gate's ticket
 * for {@link Command#QUIT}; {@code NO_TAB} where neither applies.
 */
record Intent(Command command, long id) {

    static final String TOPIC = "view.intent";
    static final Topic<Intent> INTENTS = Topic.of(TOPIC, Intent.class);

    /** Each command is a method on the view, so there is no switch to keep in step with this list. */
    enum Command {
        SAVE_FRONT {
            @Override
            void on(View view, long id) {
                view.saveFront();
            }
        },
        CLOSE_FRONT {
            @Override
            void on(View view, long id) {
                view.closeFront();
            }
        },
        NEXT_TAB {
            @Override
            void on(View view, long id) {
                view.cycle(1);
            }
        },
        PREVIOUS_TAB {
            @Override
            void on(View view, long id) {
                view.cycle(-1);
            }
        },
        /** The tab bar's selection moved. */
        FRONTED {
            @Override
            void on(View view, long id) {
                view.fronted();
            }
        },
        SAVE {
            @Override
            void on(View view, long id) {
                view.save(id);
            }
        },
        CLOSE {
            @Override
            void on(View view, long id) {
                view.close(id);
            }
        },
        CLOSE_OTHERS {
            @Override
            void on(View view, long id) {
                view.closeOthers(id);
            }
        },
        CLOSE_ALL {
            @Override
            void on(View view, long id) {
                view.closeAll();
            }
        },
        REVEAL {
            @Override
            void on(View view, long id) {
                view.reveal(id);
            }
        },
        /** The window was asked to close; {@code id} is the close gate's ticket. */
        QUIT {
            @Override
            void on(View view, long id) {
                view.quit(id);
            }
        };

        abstract void on(View view, long id);
    }
}
