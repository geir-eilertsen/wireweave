# WireWeave

<p align="center">
  <strong>Effortless WireGuard mesh networking</strong>
</p>

<p align="center">
  Self-hosted WireGuard VPN management with automated mesh topology, integrated DNS, and reverse proxy.
</p>

---

## ✨ Features

- 🌐 **DNS Zone Listing** - View and manage AWS Route53 hosted zones via REST API
- 🏗️ **Clean Architecture** - Built with hexagonal architecture principles
- 🐳 **Docker Ready** - Containerized deployment with docker-compose

## 🚀 Quick Start
```bash
git clone https://github.com/geireilertsen/wireweave
cd wireweave
cp .env.example .env
# Edit .env with your AWS credentials
docker-compose up -d
```

Visit http://localhost:8080/swagger-ui.html for API documentation

## 🏗️ Architecture

WireWeave is built using hexagonal architecture with clear separation between:
- **Domain Layer** - Core business logic
- **Application Layer** - Use cases and orchestration
- **Infrastructure Layer** - AWS Route53 adapter
- **Web Layer** - REST API with OpenAPI documentation

## 🛠️ Technology Stack

**Backend:**
- Java 21 + Spring Boot 3.x
- AWS SDK for Route53
- Springdoc OpenAPI

**Infrastructure:**
- Docker Compose

## 📋 Roadmap

- [x] Project setup and Docker containerization
- [x] AWS Route53 DNS zone listing
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
