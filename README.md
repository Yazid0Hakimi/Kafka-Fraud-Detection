# Kafka Fraud Detection

## Overview
This project involves detecting suspicious transactions in real-time using Apache Kafka, Spring Boot, and InfluxDB. Transactions are analyzed to determine if they are fraudulent or not, and metrics are visualized in Grafana.

## Architecture
Here is the system design for the project:

![System Design](images\system design.png)

## Logs Example
Below is an example of how transactions are logged in the terminal. It shows whether a transaction is suspicious or not:

![Log Example](images\image1.png)

## Features
- Real-time fraud detection using Kafka Streams
- Metrics visualization in Grafana
- Integration with InfluxDB for data storage
