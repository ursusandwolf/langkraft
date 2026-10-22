package com.langkraft.backend

class UnauthorizedException(message: String = "Invalid credentials") : Exception(message)
