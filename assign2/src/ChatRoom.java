import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ChatRoom {

    private String chatRoomName;
    private final Set<Server.ConnectionHandler> currentParticipants = new HashSet<>();
    private List<String> previousMessages;
    private final ReadWriteLock participantsLock = new ReentrantReadWriteLock();
    private final ReadWriteLock messagesLock = new ReentrantReadWriteLock();
    private final ExecutorService threadPool;

    public ChatRoom(String chatRoomName) {
        this.chatRoomName = chatRoomName;
        this.previousMessages = new ArrayList<>();
        // Create a virtual thread per task executor
        this.threadPool = Executors.newVirtualThreadPerTaskExecutor();
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

            final Server.ConnectionHandler finalUser = user;
            threadPool.submit(() -> finalUser.sendMessage("You have joined the chat room: " + chatRoomName));

            String joinMessage = "-- User " + user.getClientUserName() + " joined the chat room :) --";

            threadPool.submit(() -> {
                participantsLock.readLock().lock();
                try {
                    for (Server.ConnectionHandler participant : currentParticipants) {
                        if (participant != user) {
                            final Server.ConnectionHandler finalParticipant = participant;
                            threadPool.submit(() -> finalParticipant.sendMessage(joinMessage));
                        }
                    }
                } finally {
                    participantsLock.readLock().unlock();
                }
            });

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
            final String finalLeaveMessage = leaveMessage;
            // Use virtual thread for broadcasting leave message
            threadPool.submit(() -> broadcastMessage(finalLeaveMessage));
        }
    }

    public void broadcastMessage(String message) {
        // Use virtual thread for collecting participants and sending messages
        threadPool.submit(() -> {
            List<Server.ConnectionHandler> participantsCopy = new ArrayList<>();

            participantsLock.readLock().lock();
            try {
                participantsCopy.addAll(currentParticipants);
            } finally {
                participantsLock.readLock().unlock();
            }

            // Send message to each participant in parallel using virtual threads
            for (Server.ConnectionHandler user : participantsCopy) {
                if (user != null) {
                    final Server.ConnectionHandler finalUser = user;
                    threadPool.submit(() -> finalUser.sendMessage(message));
                }
            }
        });

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