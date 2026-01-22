#!/usr/bin/env python3
"""Simple TCP proxy to forward emulator traffic to Supabase server."""
import socket
import threading
import sys

LOCAL_HOST = '127.0.0.1'
LOCAL_PORT = 8000
REMOTE_HOST = '176.168.117.243'
REMOTE_PORT = 8000

def forward(source, destination):
    """Forward data between sockets."""
    try:
        while True:
            data = source.recv(4096)
            if not data:
                break
            destination.sendall(data)
    except Exception:
        pass
    finally:
        source.close()
        destination.close()

def handle_client(client_socket):
    """Handle incoming client connection."""
    try:
        remote_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        remote_socket.connect((REMOTE_HOST, REMOTE_PORT))

        # Start bidirectional forwarding
        client_to_remote = threading.Thread(target=forward, args=(client_socket, remote_socket))
        remote_to_client = threading.Thread(target=forward, args=(remote_socket, client_socket))

        client_to_remote.start()
        remote_to_client.start()

        client_to_remote.join()
        remote_to_client.join()
    except Exception as e:
        print(f"Connection error: {e}")
        client_socket.close()

def main():
    """Main proxy server."""
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((LOCAL_HOST, LOCAL_PORT))
    server.listen(5)

    print(f"TCP Proxy listening on {LOCAL_HOST}:{LOCAL_PORT}")
    print(f"Forwarding to {REMOTE_HOST}:{REMOTE_PORT}")

    try:
        while True:
            client_socket, addr = server.accept()
            print(f"Connection from {addr}")
            handler = threading.Thread(target=handle_client, args=(client_socket,))
            handler.start()
    except KeyboardInterrupt:
        print("\nShutting down...")
    finally:
        server.close()

if __name__ == "__main__":
    main()
