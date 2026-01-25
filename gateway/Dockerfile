# Build
FROM golang:1.22-alpine AS build
WORKDIR /src
RUN apk add --no-cache git
COPY go.mod go.sum* ./
RUN go mod download || true
COPY . .
RUN go mod tidy && CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/aura-gateway ./cmd/aura-gateway

# Runtime
FROM gcr.io/distroless/static-debian12
COPY --from=build /out/aura-gateway /aura-gateway
EXPOSE 8081
USER nonroot:nonroot
ENTRYPOINT ["/aura-gateway"]
