package se.lublin.mumla.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import se.lublin.humla.model.IChannel;
import se.lublin.humla.model.IUser;

public class RoomPathResolverTest {
    @Test
    public void resolvesExactFullPathAndBuildsItAgain() {
        FakeChannel root = new FakeChannel(0, "Root", null);
        FakeChannel publicRoom = root.add(1, "PUBLIC");
        FakeChannel main = publicRoom.add(2, "MAIN");

        assertSame(root, RoomPathResolver.resolve(root, "/"));
        assertSame(main, RoomPathResolver.resolve(root, "/PUBLIC/MAIN"));
        assertEquals("/PUBLIC/MAIN", RoomPathResolver.fullPath(main));
    }

    @Test
    public void rejectsPartialWrongCaseAndMalformedPaths() {
        FakeChannel root = new FakeChannel(0, "Root", null);
        root.add(1, "PUBLIC").add(2, "MAIN");

        assertNull(RoomPathResolver.resolve(root, "/PUBLIC" + "/missing"));
        assertNull(RoomPathResolver.resolve(root, "/public/MAIN"));
        assertNull(RoomPathResolver.resolve(root, "PUBLIC/MAIN"));
        assertNull(RoomPathResolver.resolve(root, "/PUBLIC//MAIN"));
        assertNull(RoomPathResolver.resolve(null, "/PUBLIC/MAIN"));
    }

    private static final class FakeChannel implements IChannel {
        private final int id;
        private final String name;
        private final FakeChannel parent;
        private final List<FakeChannel> children = new ArrayList<>();

        FakeChannel(int id, String name, FakeChannel parent) {
            this.id = id;
            this.name = name;
            this.parent = parent;
        }

        FakeChannel add(int childId, String childName) {
            FakeChannel child = new FakeChannel(childId, childName, this);
            children.add(child);
            return child;
        }

        @Override
        public List<? extends IUser> getUsers() {
            return Collections.emptyList();
        }

        @Override
        public int getId() {
            return id;
        }

        @Override
        public int getPosition() {
            return 0;
        }

        @Override
        public boolean isTemporary() {
            return false;
        }

        @Override
        public IChannel getParent() {
            return parent;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "";
        }

        @Override
        public byte[] getDescriptionHash() {
            return null;
        }

        @Override
        public List<? extends IChannel> getSubchannels() {
            return children;
        }

        @Override
        public int getSubchannelUserCount() {
            return 0;
        }

        @Override
        public List<? extends IChannel> getLinks() {
            return Collections.emptyList();
        }

        @Override
        public int getPermissions() {
            return 0;
        }
    }
}
