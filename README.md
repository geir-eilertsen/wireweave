# WireWeave

<p align="center">
  <strong>Effortless WireGuard mesh networking</strong>
</p>

<p align="center">
  Self-hosted WireGuard VPN management with automated mesh topology, integrated DNS, and reverse proxy.
</p>

---

## ✨ Features

- 🕸️ **Automated Mesh Networking** - Generate full-mesh WireGuard configurations automatically
- 🔐 **Secure Key Management** - Automatic key pair generation and distribution
- 🌐 **Integrated DNS** - AWS Route53 integration for dynamic endpoints
- 🔄 **Reverse Proxy** - Traefik integration with automatic SSL
- 📱 **Mobile Ready** - QR code generation for instant mobile setup
- 🐳 **Single Deployment** - Everything runs in one docker-compose stack
- 🏗️ **Clean Architecture** - Built with hexagonal architecture principles

## 🚀 Quick Start
```bash
git clone https://github.com/geireilertsen/wireweave
cd wireweave
cp .env.example .env
# Edit .env with your AWS credentials
docker-compose up -d
```

Visit http://localhost:3000

## 📖 Documentation

- [Architecture](docs/architecture.md)
- [Deployment Guide](docs/deployment.md)
- [API Reference](docs/api-reference.md)

## 🏗️ Architecture

WireWeave is built using hexagonal architecture with clear separation between:
- **Domain Layer** - Core business logic for mesh topology and VPN configuration
- **Application Layer** - Use cases and orchestration
- **Infrastructure Layer** - Adapters for AWS, Docker, and external systems
- **Web Layer** - REST API and React UI

## 🛠️ Technology Stack

**Backend:**
- Java 21 + Spring Boot 3.x
- PostgreSQL
- AWS SDK for Route53
- Docker Java API

**Frontend:**
- React + TypeScript
- Material-UI
- React Query

**Infrastructure:**
- WireGuard
- Traefik
- Docker Compose

## 📋 Roadmap

- [x] Project setup
- [ ] Mesh topology generator (v0.2)
- [ ] DNS automation (v0.3)
- [ ] Site-to-site routing (v0.4)
- [ ] Monitoring dashboard (v0.5)

## 🤝 Contributing

Contributions are welcome! Please read [CONTRIBUTING.md](CONTRIBUTING.md) for details.

## 📄 License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## 👨‍💻 Author

Created by [Geir Eilertsen](https://github.com/geireilertsen)

---

<p align="center">
  Made with ❤️ for the self-hosted community
</p>
```
