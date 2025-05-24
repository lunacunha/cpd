import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ChatRoom {
    private final String chatRoomName;
    private final String prompt;      // null for non-AI rooms
    private final boolean isAI;
    private final Set<String> users = new HashSet<>();
    private final List<String> history = new ArrayList<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public ChatRoom(String chatRoomName) {
        this(chatRoomName, null);
    }

    public ChatRoom(String chatRoomName, String prompt) {
        this.chatRoomName = chatRoomName;
        this.prompt = prompt;
        this.isAI = prompt != null;
    }

    public String getChatRoomName() {
        return chatRoomName;
    }

    public boolean isAI() {
        return isAI;
    }

    public String getPrompt() {
        return prompt;
    }

    public void addUser(String username) {
        lock.writeLock().lock();
        try {
            users.add(username);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void removeUser(String username) {
        lock.writeLock().lock();
        try {
            users.remove(username);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean hasUser(String username) {
        lock.readLock().lock();
        try {
            return users.contains(username);
        } finally {
            lock.readLock().unlock();
        }
    }

    public Set<String> getUsers() {
        lock.readLock().lock();
        try {
            return new HashSet<>(users);
        } finally {
            lock.readLock().unlock();
        }
    }

    // New method to return current user count
    public int getUserCount() {
        lock.readLock().lock();
        try {
            return users.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    public void addMessage(String message) {
        lock.writeLock().lock();
        try {
            history.add(message);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<String> getHistory() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(history);
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean isEmpty() {
        lock.readLock().lock();
        try {
            return users.isEmpty();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        ChatRoom chatRoom = (ChatRoom) obj;
        return Objects.equals(chatRoomName, chatRoom.chatRoomName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(chatRoomName);
    }
}
