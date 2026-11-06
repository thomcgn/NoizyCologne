export async function discoveryApi<T>(path: string, method = "GET", body?: unknown, signal?: AbortSignal): Promise<T> {
  const response = await fetch(`/api/v1/discovery${path}`, {
    method, signal, cache: "no-store", headers: { "Content-Type": "application/json" },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new Error(error.message || `HTTP ${response.status}`);
  }
  const text = response.status === 204 ? "" : await response.text();
  return (text ? JSON.parse(text) : null) as T;
}
