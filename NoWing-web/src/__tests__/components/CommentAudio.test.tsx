import { fireEvent, render, screen } from '@testing-library/react'
import { CommentAudio } from '../../components/CommentAudio'
jest.mock('../../components/StreamingMedia',()=>({StreamingAudio:({onSourceUnavailable}:{onSourceUnavailable:()=>void})=><button onClick={onSourceUnavailable}>Audio simulado</button>}))
test('a missing file can be retried without showing a permanent broken player',()=>{
 render(<CommentAudio url="https://media.example/audio.m4a" name="María"/>);
 fireEvent.click(screen.getByText('Audio simulado'));expect(screen.getByRole('status')).toHaveTextContent('Este audio no pudo cargarse');
 fireEvent.click(screen.getByRole('button',{name:'Reintentar audio'}));expect(screen.getByText('Audio simulado')).toBeInTheDocument();
})
