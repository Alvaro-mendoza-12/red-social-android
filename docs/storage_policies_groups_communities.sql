-- SQL for Storage Policies (RLS) for Groups and Communities Media

-- Note: These policies assume you want all authenticated users to be able to upload 
-- and everyone to be able to view the media (since they are chat messages).

-- 1. Policies for 'group-media' bucket
BEGIN;
  -- Allow public access to view files
  DO $$ 
  BEGIN
    IF NOT EXISTS (
      SELECT 1 FROM pg_policies 
      WHERE tablename = 'objects' 
      AND schemaname = 'storage' 
      AND policyname = 'Public Access for Group Media'
    ) THEN
      CREATE POLICY "Public Access for Group Media" ON storage.objects
        FOR SELECT USING (bucket_id = 'group-media');
    END IF;
  END $$;

  -- Allow authenticated users to upload files
  DO $$ 
  BEGIN
    IF NOT EXISTS (
      SELECT 1 FROM pg_policies 
      WHERE tablename = 'objects' 
      AND schemaname = 'storage' 
      AND policyname = 'Authenticated Upload Group Media'
    ) THEN
      CREATE POLICY "Authenticated Upload Group Media" ON storage.objects
        FOR INSERT WITH CHECK (bucket_id = 'group-media' AND auth.role() = 'authenticated');
    END IF;
  END $$;
COMMIT;

-- 2. Policies for 'community-media' bucket
BEGIN;
  -- Allow public access to view files
  DO $$ 
  BEGIN
    IF NOT EXISTS (
      SELECT 1 FROM pg_policies 
      WHERE tablename = 'objects' 
      AND schemaname = 'storage' 
      AND policyname = 'Public Access for Community Media'
    ) THEN
      CREATE POLICY "Public Access for Community Media" ON storage.objects
        FOR SELECT USING (bucket_id = 'community-media');
    END IF;
  END $$;

  -- Allow authenticated users to upload files
  DO $$ 
  BEGIN
    IF NOT EXISTS (
      SELECT 1 FROM pg_policies 
      WHERE tablename = 'objects' 
      AND schemaname = 'storage' 
      AND policyname = 'Authenticated Upload Community Media'
    ) THEN
      CREATE POLICY "Authenticated Upload Community Media" ON storage.objects
        FOR INSERT WITH CHECK (bucket_id = 'community-media' AND auth.role() = 'authenticated');
    END IF;
  END $$;
COMMIT;
