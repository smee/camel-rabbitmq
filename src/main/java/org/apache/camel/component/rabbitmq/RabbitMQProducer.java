/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.rabbitmq;

import java.io.IOException;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.QueueingConsumer;
import org.apache.camel.AsyncCallback;
import org.apache.camel.AsyncProcessor;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.impl.DefaultProducer;
import org.apache.camel.spi.UuidGenerator;
import org.apache.camel.util.AsyncProcessorHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The RabbitMQ producer.
 */
// todo Send to a routingKey that doesn't exist so you get a published but not routed result add check for it

public class RabbitMQProducer extends DefaultProducer implements AsyncProcessor {
    private static final transient Logger LOG = LoggerFactory.getLogger(RabbitMQProducer.class);
    private Channel channel;
    private QueueingConsumer consumer;


    public RabbitMQProducer(RabbitMQEndpoint endpoint) {
        super(endpoint);

    }

    @Override
    protected void doStart() throws Exception {
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception {
        super.doStop();
    }


    @Override
    public void process(Exchange exchange) throws Exception {
        AsyncProcessorHelper.process(this, exchange);
    }

    @Override
    public boolean process(Exchange exchange, AsyncCallback callback) {
        try {
            send(exchange);
        } catch (Exception e) {
            exchange.setException(e);
            callback.done(true);
        }
        return true;
    }

    public void send(Exchange exchange) throws Exception {

        String body = exchange.getIn().getBody(String.class);
        LOG.trace("Sending request [{}] from exchange [{}]...", body, exchange);
        RabbitMQConfiguration config = getEndpoint().getConfiguration();
        createChannel();
        String routingKey = getRoutingKey(exchange.getIn(), config);

        if (!(routingKey != null && !config.getQueue().isEmpty())) {
            configureChannel(config, channel);
        }

        if (config.isRpc()) {
            String replyQueueName = channel.queueDeclare().getQueue();

            consumer = new QueueingConsumer(channel);
            channel.basicConsume(replyQueueName, true, consumer);

            UuidGenerator uuidGenerator = getEndpoint().getCamelContext().getUuidGenerator();
            String corrId = uuidGenerator.generateUuid();

            AMQP.BasicProperties props = new AMQP.BasicProperties
                .Builder()
                .correlationId(corrId)
                .replyTo(replyQueueName)
                .build();

            channel.basicPublish(config.getExchange(), routingKey, props, body.getBytes());

            String response = waitForReply(exchange, corrId);
            exchange.getOut().setBody(response);

        } else {
            if (body == null || body.length() == 0) {
                exchange.setException(new Exception("Message body is null"));
                return;
            }

            AMQP.BasicProperties props = config.getMessageProperties();
            if (props == null) {
                props = new AMQP.BasicProperties();
            }
            props = props.builder().messageId(exchange.getIn().getMessageId()).build();
            channel.basicPublish(config.getExchange(), routingKey, props, body.getBytes());
        }

        Message message = getMessageForResponse(exchange);
    }

    private String waitForReply(Exchange exchange, String corrId) throws InterruptedException {
        String response;
        while (true) {
            QueueingConsumer.Delivery delivery = consumer.nextDelivery();
            if (delivery.getProperties().getCorrelationId().equals(corrId)) {
                response = new String(delivery.getBody());
                exchange.getOut().setBody(response);
                break;
            }
        }
        return response;
    }


    @Override
    public RabbitMQEndpoint getEndpoint() {
        return (RabbitMQEndpoint) super.getEndpoint();
    }

    private void createChannel() throws Exception {
        if (channel == null) {

            Connection connection = getEndpoint().getConnection();
            RabbitMQConfiguration config = getEndpoint().getConfiguration();

            channel = connection.createChannel();

            LOG.trace("-------------- Producer -----------------");
            LOG.trace(config.toString());
            LOG.trace("-----------------------------------------");

            if (!config.getExchange().isEmpty()) {
                channel.exchangeDeclare(config.getExchange(), config.getExchangeType());
                String queueName = channel.queueDeclare().getQueue();
                if (config.getBindingKeys().size() > 0) {
                    setBindingKeys(config, queueName);
                } else {
                    channel.queueBind(queueName, config.getExchange(), "");
                }
            } else {
                String queueName = config.getQueue();
                channel.queueDeclare(queueName, config.getDurable(), false, false, null);
            }
        }
    }

    // todo move to endpoitn dupe code
    private void setBindingKeys(RabbitMQConfiguration config, String queueName) throws IOException {
        for (String bindingKey : config.getBindingKeys()) {
            channel.queueBind(queueName, config.getExchange(), bindingKey);
        }
    }

    private void configureChannel(RabbitMQConfiguration config, Channel channel) throws IOException {

        if (!config.getExchange().isEmpty()) {
            channel.exchangeDeclare(config.getExchange(), config.getExchangeType());
        } else {
            channel.queueDeclare(config.getQueue(), config.getDurable(), false, false, null);
        }
    }

    private String getRoutingKey(Message msg, RabbitMQConfiguration config) {
        String key = config.getRoutingKey();

        String header = (String) msg.getHeader(RabbitMQConstants.ROUTING_KEY);

        if (config.getExchange().isEmpty()) {
            key = config.getQueue();
        }
        if (header != null) {
            if (!config.getExchange().isEmpty() || !config.getQueue().isEmpty()) {
                key = header;
            } else {
                LOG.warn("No exchange or queue set, ignoring routing key: " + header);
            }
        }
        return key;
    }

    private Message getMessageForResponse(Exchange exchange) {
        if (exchange.getPattern().isOutCapable()) {
            Message out = exchange.getOut();
            out.copyFrom(exchange.getIn());
            return out;
        }

        return exchange.getIn();
    }


}
