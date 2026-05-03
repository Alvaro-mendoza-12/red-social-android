-- Add allow_replies column to moments table
ALTER TABLE public.moments 
ADD COLUMN IF NOT EXISTS allow_replies BOOLEAN DEFAULT true;
