package dev.vexelray.framework.shell;

import dev.vexelray.framework.core.Phase;
import sibarum.atchung.Tick;
import sibarum.atchung.Topic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A run replayed: what its logic took in, run again on a fresh set of its components, tick by tick, and every send
 * compared with the one the run made (ROADMAP <i>Done means</i> 5: the same input messages, in the same order, produce
 * the same widget writes, in either mode). The answer to {@code replay} on the automation socket.
 *
 * <p><b>Where it runs.</b> In this process, on the thread that asks, so the messages need no encoding and a
 * fingerprint ({@code hashCode}) compares within the process. The components are built by a fresh wiring of the
 * application ({@link Wiring#again}) on a shell of their own ({@link Shell#replaying}): its own bus, a silent timer, and
 * no jobs or sources, whose answers are among what the recording says arrived. Only {@link Phase#CONFIG} and
 * {@link Phase#MODEL} are built, which is where components and jobs are; a component built later, because it takes the
 * GUI, is not replayed, and when the replay's inboxes are not the run's it says so rather than compare.
 *
 * <p><b>From the start.</b> A component's state is its own, so a replay begins where the run did, at launch, and goes
 * as far as the recording has whole ticks.
 *
 * <p><b>Chain runs.</b> A fresh set of components numbers its chain runs afresh, so a run's number in the recording is
 * mapped to the replay's by the sends that carry them, in order, and a message arriving in that run is staged in the
 * replay's.
 */
final class Replay {

    /** How many differences {@code replay} lists; it counts them all. */
    static final int LISTED = 16;

    /** How much of a send's text a difference shows. */
    private static final int SHOWN = 160;

    private Replay() {
    }

    /** {@code replay}'s answer for {@code run}: {@code ok replay ...} and the differences, or {@code err replay: ...}. */
    static String run(Shell run) {
        InputRecording recording = run.recording();
        if (recording == null) {
            return "err replay: this run keeps no recording; launch it --replayable (or with VEXELRAY_REPLAYABLE=1)";
        }
        Wiring again = run.again();
        if (again == null) {
            return "err replay: this application's wiring cannot make another of itself (Wiring.again), so there "
                    + "is nothing to build the components again from";
        }
        InputRecording.Taken taken = recording.take();
        Shell shell = Shell.replaying(run);
        try {
            shell.phase(Phase.CONFIG);
            again.config(shell);
            shell.phase(Phase.MODEL);
            again.model(shell);
            List<String> theirs = run.logicInboxes();
            List<String> mine = shell.logicInboxes();
            if (!theirs.equals(mine)) {
                return "err replay: the components built again are not the run's: " + mine + " against " + theirs
                        + " (a component built after MODEL, because it takes the GUI, is not replayed)";
            }
            if (mine.isEmpty()) {
                return "ok replay ticks=0 sends=0 differ=0" + (taken.full ? " full" : "");
            }
            return compare(taken, shell.logicTick());
        } catch (RuntimeException | Error e) {
            return "err replay: building the components again failed: " + e;
        } finally {
            shell.disposer().close();
        }
    }

    /** Run each recorded tick on {@code tick} and compare its sends with the recording's. */
    private static String compare(InputRecording.Taken taken, Tick tick) {
        Sends replayed = new Sends();
        tick.record(replayed);
        Map<Long, Long> runs = new HashMap<>();
        List<String> listed = new ArrayList<>();
        int differ = 0;
        int sends = 0;
        long number = 0;
        for (int t = 0; t < taken.ticks; t++) {
            if (taken.rounds(t) > 0) {
                number++;
            }
            for (int i = taken.arrivedFrom(t); i < taken.arrivedTo(t); i++) {
                long run = taken.arrivedRun(i);
                tick.restage(taken.inbox(i), taken.subscription(i), taken.event(i), runs.getOrDefault(run, run));
            }
            replayed.clear();
            try {
                tick.runFor(taken.rounds(t));
            } catch (RuntimeException | Error e) {
                listed.add("#" + number + " threw " + oneLine(e.toString()));
                differ++;
                return answer(t + 1, sends, differ, taken.full, listed);
            }
            int from = taken.sentFrom(t);
            int count = Math.max(taken.sentTo(t) - from, replayed.size());
            for (int j = 0; j < count; j++) {
                sends++;
                boolean recorded = from + j < taken.sentTo(t);
                boolean again = j < replayed.size();
                if (recorded && again) {
                    long was = taken.sentRun(from + j);
                    if (was != 0 && replayed.run(j) != 0) {
                        runs.putIfAbsent(was, replayed.run(j));
                    }
                    Topic<?> topic = taken.topic(from + j);
                    if (topic.name().equals(replayed.topic(j).name())
                            && taken.fingerprint(from + j) == Objects.hashCode(replayed.event(j))) {
                        continue;
                    }
                }
                differ++;
                if (listed.size() < LISTED) {
                    listed.add(difference(number, j, taken, from + j, recorded, replayed, again));
                }
            }
        }
        return answer(taken.ticks, sends, differ, taken.full, listed);
    }

    private static String difference(long tick, int send, InputRecording.Taken taken, int at, boolean recorded,
                                     Sends replayed, boolean again) {
        String topic = recorded ? taken.topic(at).name() : replayed.topic(send).name();
        String was = recorded ? Integer.toHexString(taken.fingerprint(at)) : "none";
        String now = again ? Integer.toHexString(Objects.hashCode(replayed.event(send))) : "none";
        String what = again ? oneLine(String.valueOf(replayed.event(send))) : "";
        if (!again || recorded && !taken.topic(at).name().equals(replayed.topic(send).name())) {
            what = again ? "sent " + replayed.topic(send).name() + " instead: " + what : "not sent again";
        }
        return "#" + tick + " " + send + " " + topic + " recorded=" + was + " replayed=" + now + " " + what;
    }

    private static String answer(int ticks, int sends, int differ, boolean full, List<String> listed) {
        StringBuilder out = new StringBuilder("ok replay ticks=").append(ticks).append(" sends=").append(sends)
                .append(" differ=").append(differ).append(full ? " full" : "");
        for (String line : listed) {
            out.append('\n').append(line);
        }
        return out.toString();
    }

    /** {@code text} on one line, at most {@link #SHOWN} long: a reply line on the socket. */
    private static String oneLine(String text) {
        String line = text.replace('\r', ' ').replace('\n', ' ');
        return line.length() <= SHOWN ? line : line.substring(0, SHOWN - 1) + "…";
    }

    /** One replayed tick's sends, in order. */
    private static final class Sends implements Tick.Recorder {
        private final List<Topic<?>> topics = new ArrayList<>();
        private final List<Object> events = new ArrayList<>();
        private final List<Long> runs = new ArrayList<>();

        @Override
        public void arrived(int inbox, int subscription, Object event, long run) {
        }

        @Override
        public void sent(Topic<?> topic, Object event, long run) {
            topics.add(topic);
            events.add(event);
            runs.add(run);
        }

        @Override
        public void ran(int rounds, boolean carried) {
        }

        void clear() {
            topics.clear();
            events.clear();
            runs.clear();
        }

        int size() {
            return topics.size();
        }

        Topic<?> topic(int i) {
            return topics.get(i);
        }

        Object event(int i) {
            return events.get(i);
        }

        long run(int i) {
            return runs.get(i);
        }
    }
}
