import java.util.*;
import java.util.concurrent.locks.*;

public class ChatRoom {
    private final String chatRoomName;
    private final Set<String> users = new HashSet<>();
    private final ReadWriteLock usersLock = new ReentrantReadWriteLock();

    public ChatRoom(String chatRoomName) {
        this.chatRoomName = chatRoomName;
    }

    public String getChatRoomName() {
        return chatRoomName;
    }

    public void addUser(String username) {
        usersLock.writeLock().lock();
        try {
            users.add(username);
        } finally {
            usersLock.writeLock().unlock();
        }
    }

    public void removeUser(String username) {
        usersLock.writeLock().lock();
        try {
            users.remove(username);
        } finally {
            usersLock.writeLock().unlock();
        }
    }

    public boolean hasUser(String username) {
        usersLock.readLock().lock();
        try {
            return users.contains(username);
        } finally {
            usersLock.readLock().unlock();
        }
    }

    public Set<String> getUsers() {
        usersLock.readLock().lock();
        try {
            return new HashSet<>(users);
        } finally {
            usersLock.readLock().unlock();
        }
    }

    public boolean isEmpty() {
        usersLock.readLock().lock();
        try {
            return users.isEmpty();
        } finally {
            usersLock.readLock().unlock();
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