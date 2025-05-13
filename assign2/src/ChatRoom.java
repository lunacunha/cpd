import java.io.IOException;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ChatRoom {

    private String chatRoomName;
    private final Set<Server.ConnectionHandler> currentParticipants = new HashSet<>();
    private List<String> previousMessages;
    private final ReadWriteLock participantsLock = new ReentrantReadWriteLock();
    private final ReadWriteLock messagesLock = new ReentrantReadWriteLock();

    public ChatRoom(String chatRoomName) {
        this.chatRoomName = chatRoomName;
        this.previousMessages = new ArrayList<>();
    }

    public String getChatRoomName() {
        return chatRoomName;
    }

    public void addUserToChatRoom(Server.ConnectionHandler user) {
        try {
            participantsLock.writeLock().lock();
            try {
                currentParticipants.add(user);
            } finally {
                participantsLock.writeLock().unlock();
            }

            user.sendMessage("You have joined the chat room: " + chatRoomName);
            sendPreviousMessages(user);

            // Broadcast to other users (excluding the one who just joined)
            String joinMessage = "-- User " + user.getClientUserName() + " joined the chat room :) --";

            participantsLock.readLock().lock();
            try {
                for (Server.ConnectionHandler participant : currentParticipants) {
                    if (participant != user) {
                        participant.sendMessage(joinMessage);
                    }
                }
            } finally {
                participantsLock.readLock().unlock();
            }

            messagesLock.writeLock().lock();
            try {
                previousMessages.add(joinMessage);
            } finally {
                messagesLock.writeLock().unlock();
            }
        }
        catch (Exception e) {
            System.err.println("Error adding user to chat room: " + e.getMessage());
        }
    }

    public void removeUserFromChatRoom(Server.ConnectionHandler user) {
        boolean shouldBroadcast = false;
        String leaveMessage = null;

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

        if (shouldBroadcast) {
            broadcastMessage(leaveMessage);
        }
    }

    public void broadcastMessage(String message) {
        participantsLock.readLock().lock();
        try {
            for (Server.ConnectionHandler user : currentParticipants) {
                if (user != null) {
                    user.sendMessage(message);
                }
            }
        } finally {
            participantsLock.readLock().unlock();
        }

        if (!message.startsWith("/")) {
            messagesLock.writeLock().lock();
            try {
                previousMessages.add(message);
            } finally {
                messagesLock.writeLock().unlock();
            }
        }
    }

    public void sendPreviousMessages(Server.ConnectionHandler user) {
        try {
            if (user != null) {
                messagesLock.readLock().lock();
                try {
                    if (!previousMessages.isEmpty()) {
                        user.sendMessage("--- Previous messages ---");
                        for (String message : previousMessages) {
                            user.sendMessage(message);
                        }
                        user.sendMessage("-------------------------");
                    }
                } finally {
                    messagesLock.readLock().unlock();
                }
            }
        } catch (Exception e) {
            System.err.println("Error sending previous messages: " + e.getMessage());
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