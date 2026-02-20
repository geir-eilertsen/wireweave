# WireWeave

<p align="center">
  <strong>Effortless WireGuard mesh networking</strong>
</p>

<p align="center">
  Self-hosted infrastructure management platform with service discovery, DNS integration, and reverse proxy configuration.
</p>

---

## ✨ Features

- 🔍 **Service Discovery** - Automatically discover Docker containers via Portainer with exposed port mapping
- 🌐 **DNS Management** - View and manage AWS Route53 hosted zones and CNAME records via REST API
- 🔀 **Reverse Proxy Integration** - Parse Traefik configurations to discover routes with authentication middleware
- 🛡️ **WireGuard Config Parsing** - Read and parse WireGuard interface and peer configurations
- 🏗️ **Clean Architecture** - Built with hexagonal architecture principles for maintainability
- 🐳 **Docker Ready** - Containerized deployment with docker-compose
- 📚 **OpenAPI Documentation** - Interactive API documentation with Swagger UI

## 🚀 Getting Started

### Prerequisites

- Docker and Docker Compose
- Java 21 (for local development)
- Maven 3.9+ (for local development)
- AWS credentials with Route53 access (optional, for DNS features)

### Running with Docker (Recommended)

1. **Clone the repository**
   ```bash
   git clone https://github.com/geireilertsen/wireweave
   cd wireweave
   ```

2. **Configure environment variables**

   Create a `.env` file with your configuration:
   ```bash
   WIREWEAVE_AWS_KEY=your_aws_access_key
   WIREWEAVE_AWS_SECRET=your_aws_secret_key
   ```

3. **Start the application**
   ```bash
   docker-compose up -d
   ```

4. **Access the application**
   - API: http://localhost:8888
   - Swagger UI: http://localhost:8888/swagger-ui.html

### Running Locally (Development)

1. **Clone the repository**
   ```bash
   git clone https://github.com/geireilertsen/wireweave
   cd wireweave
   ```

2. **Set environment variables**
   ```bash
   export WIREWEAVE_AWS_KEY=your_aws_access_key
   export WIREWEAVE_AWS_SECRET=your_aws_secret_key
   ```

3. **Build and run**
   ```bash
   mvn clean package
   java -jar target/wireweave-1.0.0-SNAPSHOT.jar
   ```

   Or run directly with Maven:
   ```bash
   mvn spring-boot:run
   ```

4. **Access the application**
   - API: http://localhost:8080
   - Swagger UI: http://localhost:8080/swagger-ui.html

### API Endpoints

- `GET /dns/zones` - List all DNS zones
- `GET /dns/zones/{zoneName}/records` - List CNAME records for a zone
- `GET /hosted-services/discover` - Discover hosted services from Docker, Traefik, and WireGuard configurations

## 🏗️ Architecture

WireWeave is built using hexagonal architecture with clear separation between:
- **Domain Layer** - Core business logic and entities
- **Application Layer** - Use cases and orchestration
- **Infrastructure Layer** - Adapters for AWS Route53, Docker/Portainer, Traefik, and WireGuard
- **Web Layer** - REST API with OpenAPI documentation

## 🛠️ Technology Stack

**Backend:**
- Java 21 + Spring Boot 3.5
- AWS SDK for Route53
- Docker Java Client
- Springdoc OpenAPI
- SnakeYAML for configuration parsing

**Infrastructure:**
- Docker Compose
- Maven

## 📋 Roadmap

- [x] Project setup and Docker containerization
- [x] AWS Route53 DNS zone listing and CNAME record filtering
- [x] Traefik reverse proxy configuration parsing
- [x] Docker container discovery via Portainer
- [x] WireGuard configuration file parsing
- [x] Hosted service discovery with unified REST API
- [ ] WireGuard mesh topology generator
- [ ] Automated DNS record management
- [ ] Site-to-site routing configuration
- [ ] Monitoring and management dashboard

## 📄 License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## 👨‍💻 Author

Created by [Geir Eilertsen](https://github.com/geireilertsen)

---

<p align="center">
  Made with ❤️ for the self-hosted community
</p>
```
