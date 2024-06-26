package com.example.websocket;

import com.example.websocket.model.RTCConfiguration;
import com.example.websocket.model.RTCIceServer;
import com.example.websocket.model.WebRtcSignalingMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WebRtcSignalHandler extends AbstractWebSocketHandler {
    private static final String url = "turn:clinicnew.life:3478";
    private static final String username = "user1";
    private static final String credential = "pass1key0";
    private static final Logger logger = LogManager.getLogger(WebRtcSignalHandler.class);
    private static final Map<String, WebSocketSession> sessionMap = new ConcurrentHashMap<>();
    private static final BiMap<String, String> registeredSessionIdMap = HashBiMap.create();
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        logger.info("Connection established with id {}.", session.getId());
        WebSocketSession oldSession = sessionMap.put(session.getId(), session);

        // Close the old session if exists.
        if (oldSession != null)
            oldSession.close();

        // Send the configuration to the client.
        session.sendMessage(new TextMessage(
                objectMapper.writeValueAsString(new WebRtcSignalingMessage(objectMapper.writeValueAsString(new RTCConfiguration().withIceServer(new RTCIceServer().withUrl(url).withUsername(username).withCredential(credential)))))));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        logger.info("Received text message {} from {}.", message, session.getId());

        try {
            WebRtcSignalingMessage webRtcSignalingMessage = objectMapper.readValue(message.getPayload(),
                    WebRtcSignalingMessage.class);
            logger.info("Parsed message: {}", webRtcSignalingMessage);

            /* Set the sender peer's session ID as from address. */
            webRtcSignalingMessage.setFrom(session.getId());

            switch (webRtcSignalingMessage.getType()) {
                case REGISTER:
                    String id = webRtcSignalingMessage.getPayload();

                    /*
                     * Replace the session ID in session map with agent ID to identify receiver of
                     * request. sessionMap.put(webRtcSignalingMessage.getPayload(),
                     * sessionMap.remove(session.getId()));
                     */
                    String oldSessionId = registeredSessionIdMap.put(id, session.getId());

                    /* Close the session if already registered. */
                    if (oldSessionId != null) {
                        sessionMap.remove(oldSessionId).close();
                    }

                    break;
                case REQUEST:
                    /*
                     * If agent needs to register on the fly, do the stuff here.
                     */

                    /*
                     * Other peer identified using an ID and should be registered by active socket
                     * connection.
                     */
                    if (registeredSessionIdMap.containsKey(webRtcSignalingMessage.getTo())) {
                        /* Set the remote peer's registered session ID as to address. */
                        webRtcSignalingMessage.setTo(registeredSessionIdMap.get(webRtcSignalingMessage.getTo()));
                        /* Forward the message to other peer. */
                        sessionMap.get(webRtcSignalingMessage.getTo())
                                .sendMessage(new TextMessage(objectMapper.writeValueAsString(webRtcSignalingMessage)));
                    } else {
                        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(
                                new WebRtcSignalingMessage().withError("Video source not available."))));
                        logger.warn("{} yet not registered.", webRtcSignalingMessage.getTo());
                    }

                    break;
                case RESPONSE:
                case OFFER:
                case ANSWER:
                case CANDIDATE:
                    sessionMap.get(webRtcSignalingMessage.getTo())
                            .sendMessage(new TextMessage(objectMapper.writeValueAsString(webRtcSignalingMessage)));
                    break;
                default:
                    logger.error("Invalid WebRTC signaling message type {}.", webRtcSignalingMessage.getType());
            }
        } catch (JsonProcessingException exception) {
            logger.error("Invalid WebRTC signaling message: ", exception);
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        logger.error("Received binary message {} from {}.", message, session.getId());
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) throws Exception {
        logger.error("Received pong message {} from {}.", message, session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        logger.info("Connection closed with id {} as {}.", session.getId(), status.getReason());
        registeredSessionIdMap.inverse().remove(session.getId());
        sessionMap.remove(session.getId());
    }
}
