// ChatRoom.java
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ChatRoom {

    private final String chatRoomName;
    private final Set<Server.ConnectionHandler> currentParticipants = new HashSet<>();
    private final List<String> previousMessages = new ArrayList<>();
    private final ReadWriteLock participantsLock = new ReentrantReadWriteLock();
    private final ReadWriteLock messagesLock     = new ReentrantReadWriteLock();

    public ChatRoom(String chatRoomName) {
        this.chatRoomName = chatRoomName;
    }

    public String getChatRoomName() {
        return chatRoomName;
    }

    public void addUserToChatRoom(Server.ConnectionHandler user) {
        // 1) add to set
        participantsLock.writeLock().lock();
        try {
            currentParticipants.add(user);
        } finally {
            participantsLock.writeLock().unlock();
        }

        // 2) notify the new user
        new Thread(() ->
                user.sendMessage("You have joined the chat room: " + chatRoomName)
        ).start();

        // 3) broadcast join to others
        String joinMessage = "-- User " + user.getClientUserName() + " joined the chat room :) --";
        new Thread(() -> {
            participantsLock.readLock().lock();
            try {
                for (Server.ConnectionHandler participant : currentParticipants) {
                    if (participant != user) {
                        new Thread(() -> participant.sendMessage(joinMessage)).start();
                    }
                }
            } finally {
                participantsLock.readLock().unlock();
            }
        }).start();

        // 4) record history
        messagesLock.writeLock().lock();
        try {
            previousMessages.add(joinMessage);
        } finally {
            messagesLock.writeLock().unlock();
        }
    }

    public void removeUserFromChatRoom(Server.ConnectionHandler user) {
        String leaveMessage = null;
        boolean shouldBroadcast = false;

        participantsLock.writeLock().lock();
        try {
            currentParticipants.remove(user);
            if (!currentParticipants.isEmpty()) {
                leaveMessage = "-- User " + user.getClientUserName() + " left the chat room --";
                messagesLock.writeLock().lock();
                try {
                    previousMessages.add(leaveMessage);
                } finally {
                    messagesLock.writeLock().unlock();
                }
                shouldBroadcast = true;
            }
        } finally {
            participantsLock.writeLock().unlock();
        }

        if (shouldBroadcast && leaveMessage != null) {
            String msg = leaveMessage;
            new Thread(() -> broadcastMessage(msg)).start();
        }
    }

    public void broadcastMessage(String message) {
        // dispatch senders in background
        new Thread(() -> {
            List<Server.ConnectionHandler> snapshot = new ArrayList<>();
            participantsLock.readLock().lock();
            try {
                snapshot.addAll(currentParticipants);
            } finally {
                participantsLock.readLock().unlock();
            }
            for (Server.ConnectionHandler user : snapshot) {
                if (user != null) {
                    new Thread(() -> user.sendMessage(message)).start();
                }
            }
        }).start();

        // unless it's a slash‐command, record it
        if (!message.startsWith("/")) {
            messagesLock.writeLock().lock();
            try {
                previousMessages.add(message);
            } finally {
                messagesLock.writeLock().unlock();
            }
        }
    }

    public int getParticipantCount() {
        participantsLock.readLock().lock();
        try {
            return currentParticipants.size();
        } finally {
            participantsLock.readLock().unlock();
        }
    }
}
