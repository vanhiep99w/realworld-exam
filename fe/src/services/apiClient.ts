import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios';

const REQUEST_ID_HEADER = 'X-Request-ID';

export interface ApiError {
  status: number;
  message: string;
  requestId: string;
  timestamp: string;
}

export class ApiRequestError extends Error {
  public readonly status: number;
  public readonly requestId: string;
  public readonly timestamp: string;

  constructor(error: ApiError) {
    super(error.message);
    this.name = 'ApiRequestError';
    this.status = error.status;
    this.requestId = error.requestId;
    this.timestamp = error.timestamp;
  }

  toUserMessage(): string {
    return `${this.message} (Mã hỗ trợ: ${this.requestId})`;
  }
}

export const apiClient = axios.create({
  baseURL: 'http://localhost:8080',
  headers: {
    'Content-Type': 'application/json',
  },
});

// Request interceptor: inject X-Request-ID
apiClient.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    config.headers[REQUEST_ID_HEADER] = crypto.randomUUID();
    return config;
  }
);

// Response interceptor: transform errors
apiClient.interceptors.response.use(
  (response) => response,
  (error: AxiosError<ApiError>) => {
    const requestId = error.response?.headers?.[REQUEST_ID_HEADER.toLowerCase()] 
      || error.response?.data?.requestId 
      || 'unknown';
    
    const apiError: ApiError = {
      status: error.response?.status || 500,
      message: error.response?.data?.message || error.message || 'Unknown error',
      requestId,
      timestamp: error.response?.data?.timestamp || new Date().toISOString(),
    };

    return Promise.reject(new ApiRequestError(apiError));
  }
);
