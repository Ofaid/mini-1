import type { Metadata } from 'next';
import './globals.css';

export const metadata: Metadata = {
  title: 'Minimum Admin',
  description: 'Secure configuration console for Minimum radio devices'
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body>
        {children}
      </body>
    </html>
  );
}
